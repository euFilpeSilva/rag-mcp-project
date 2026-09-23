package com.example.ragmcp.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Serviço responsável pela "ingestão" de documentos: transforma um
 * arquivo PDF/TXT em vários pedaços de texto (chunks), gera o embedding
 * (vetor) de cada um via Ollama, e salva tudo no banco vetorial
 * (PGVector), para que depois o {@code RagQueryService} consiga
 * encontrá-los por similaridade.
 *
 * <p>Fluxo passo a passo de {@link #ingestFile(Path)}:</p>
 * <ol>
 *   <li>Valida se o arquivo existe e tem extensão suportada
 *       ({@code .pdf} ou {@code .txt}).</li>
 *   <li>Extrai o texto e quebra em "segments" (chunks) menores, usando
 *       o {@code DocumentSplitter} do LangChain4j — necessário porque o
 *       modelo de embeddings e o LLM têm limite de tamanho de entrada, e
 *       chunks menores tendem a gerar buscas mais precisas.</li>
 *   <li>Para cada chunk, calcula um hash SHA-256 do texto — se já existe
 *       um chunk com o mesmo hash no banco, ele é pulado (evita
 *       duplicar dados ao reingerir o mesmo arquivo).</li>
 *   <li>Gera o embedding do chunk (chamando o Ollama) e salva no
 *       PGVector junto com metadados (nome do arquivo, página, hash).</li>
 * </ol>
 */
@Service
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionServiceImpl.class);
    private static final String SOURCE_FILE = "source_file";
    private static final String PAGE_NUMBER = "page_number";
    private static final String CHUNK_HASH = "chunk_hash";

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentSplitter splitter;

    public DocumentIngestionServiceImpl(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            JdbcTemplate jdbcTemplate,
            @Value("${rag.chunk.max-tokens}") int maxTokens,
            @Value("${rag.chunk.overlap-tokens}") int overlapTokens) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.jdbcTemplate = jdbcTemplate;
        this.splitter = DocumentSplitters.recursive(maxTokens, overlapTokens);
    }

    /**
     * Ingere um único arquivo: extrai texto, quebra em chunks, ignora
     * duplicados (mesmo hash) e salva o restante no banco vetorial.
     * Cada exceção é capturada e convertida em um {@link IngestionResult}
     * com {@code success=false}, para que a API REST nunca quebre por
     * causa de um arquivo problemático.
     */
    @Override
    public IngestionResult ingestFile(Path filePath) {
        try {
            validateFile(filePath);
            List<TextSegment> segments = extractSegments(filePath);
            int created = 0;
            int skipped = 0;

            for (TextSegment segment : segments) {
                String chunkHash = sha256(segment.text());
                if (chunkExists(chunkHash)) {
                    skipped++;
                    continue;
                }
                Metadata metadata = segment.metadata() == null ? new Metadata() : segment.metadata().copy();
                metadata.put(CHUNK_HASH, chunkHash);
                TextSegment segmentWithHash = TextSegment.from(segment.text(), metadata);
                Embedding embedding = embeddingModel.embed(segmentWithHash).content();
                embeddingStore.add(embedding, segmentWithHash);
                created++;
            }

            return new IngestionResult(filePath.getFileName().toString(), created, skipped, true, null);
        } catch (Exception e) {
            log.warn("Falha ao ingerir arquivo {}", filePath, e);
            return new IngestionResult(filePath.getFileName().toString(), 0, 0, false, e.getMessage());
        }
    }

    /**
     * Ingere todos os arquivos suportados (.pdf/.txt) de um diretório,
     * um por um, retornando o relatório de cada um. Não é recursivo —
     * só olha os arquivos diretamente dentro da pasta informada.
     */
    @Override
    public List<IngestionResult> ingestDirectory(Path directoryPath) {
        if (!Files.isDirectory(directoryPath)) {
            throw new IllegalArgumentException("Diretório inválido: " + directoryPath);
        }
        try (Stream<Path> stream = Files.list(directoryPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .sorted()
                    .map(this::ingestFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao listar diretório: " + directoryPath, e);
        }
    }

    /** Escolhe o extrator certo (PDF ou TXT) de acordo com a extensão do arquivo. */
    private List<TextSegment> extractSegments(Path filePath) throws IOException {
        String filename = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".pdf")) {
            return extractPdfSegments(filePath);
        }
        if (filename.endsWith(".txt")) {
            return extractTextSegments(filePath);
        }
        throw new IllegalArgumentException("Formato não suportado: " + filePath.getFileName());
    }

    /**
     * Extrai texto de um PDF página por página (usando Apache PDFBox) e
     * quebra cada página em chunks menores com o {@code splitter}. Cada
     * chunk guarda o número da página de origem — é isso que permite ao
     * usuário final saber exatamente onde, no documento, a resposta foi
     * encontrada (campo {@code pageNumber} em {@code SourceReference}).
     */
    private List<TextSegment> extractPdfSegments(Path filePath) throws IOException {
        List<TextSegment> segments = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                if (text == null || text.isBlank()) {
                    continue;
                }
                Metadata metadata = Metadata.from(SOURCE_FILE, filePath.getFileName().toString());
                metadata.put(PAGE_NUMBER, page);
                segments.addAll(splitter.split(Document.from(text, metadata)).stream()
                        .map(segment -> TextSegment.from(segment.text(), mergeMetadata(segment.metadata(), metadata)))
                        .toList());
            }
        }
        return segments;
    }

    /**
     * Extrai texto de um arquivo .txt inteiro e quebra em chunks. Como
     * não existe conceito de "página" em .txt, usamos o índice
     * sequencial do chunk (1, 2, 3...) como um substituto aproximado de
     * {@code pageNumber}, só para referência.
     */
    private List<TextSegment> extractTextSegments(Path filePath) throws IOException {
        String text = Files.readString(filePath, StandardCharsets.UTF_8);
        Metadata baseMetadata = Metadata.from(SOURCE_FILE, filePath.getFileName().toString());
        List<TextSegment> splitSegments = splitter.split(Document.from(text, baseMetadata));
        List<TextSegment> result = new ArrayList<>(splitSegments.size());
        for (int i = 0; i < splitSegments.size(); i++) {
            Metadata metadata = mergeMetadata(splitSegments.get(i).metadata(), baseMetadata);
            metadata.put(PAGE_NUMBER, i + 1);
            result.add(TextSegment.from(splitSegments.get(i).text(), metadata));
        }
        return result;
    }

    /** Combina os metadados gerados pelo splitter com os metadados base (nome do arquivo). */
    private Metadata mergeMetadata(Metadata segmentMetadata, Metadata baseMetadata) {
        Metadata merged = baseMetadata.copy();
        if (segmentMetadata != null) {
            merged.putAll(segmentMetadata.toMap());
        }
        return merged;
    }

    private void validateFile(Path filePath) {
        if (filePath == null || !Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Arquivo inválido: " + filePath);
        }
        if (!isSupported(filePath)) {
            throw new IllegalArgumentException("Formato não suportado: " + filePath.getFileName());
        }
    }

    private boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || name.endsWith(".txt");
    }

    /** Consulta rápida no Postgres para saber se um chunk (pelo hash) já foi ingerido antes. */
    private boolean chunkExists(String chunkHash) {
        Integer result = jdbcTemplate.query(
                "SELECT 1 FROM document_chunks WHERE chunk_hash = ?",
                rs -> rs.next() ? 1 : null,
                chunkHash);
        return result != null;
    }

    /** Gera o hash SHA-256 do texto do chunk, usado como "impressão digital" anti-duplicata. */
    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
