package com.example.ragmcp.config;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Implementação "na mão" (via JDBC puro) do contrato {@link EmbeddingStore}
 * do LangChain4j, usando a extensão {@code pgvector} do Postgres como
 * banco vetorial.
 *
 * <p>Por que implementar isso na mão em vez de usar uma integração
 * pronta? Porque no momento em que este projeto foi escrito, o conector
 * oficial {@code langchain4j-pgvector} ainda estava em versão beta, e
 * esta classe permite controlar exatamente o schema da tabela
 * {@code document_chunks} (incluindo colunas extras como
 * {@code source_file}, {@code page_number} e {@code chunk_hash} — este
 * último usado para não ingerir o mesmo chunk duas vezes).</p>
 *
 * <p>Conceito-chave: um "embedding" é um vetor de números (ex: 768
 * posições/dimensões) que representa o significado de um texto. A
 * extensão {@code pgvector} permite armazenar esses vetores em uma coluna
 * do tipo {@code vector} e calcular a "distância" (aqui, distância de
 * cosseno, operador {@code <=>}) entre vetores diretamente em SQL — é
 * assim que a busca por similaridade funciona: quanto menor a distância
 * (ou maior {@code 1 - distância}, chamado de similaridade), mais
 * parecido semanticamente o chunk é com a pergunta.</p>
 */
public class JdbcPgVectorEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final Logger log = LoggerFactory.getLogger(JdbcPgVectorEmbeddingStore.class);
    private static final String SOURCE_FILE = "source_file";
    private static final String PAGE_NUMBER = "page_number";
    private static final String CHUNK_HASH = "chunk_hash";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final String datasourceUrl;
    private final String username;
    private final String password;
    private final String tableName;
    private final int dimension;

    public JdbcPgVectorEmbeddingStore(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            String datasourceUrl,
            String username,
            String password,
            String tableName,
            int dimension) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.datasourceUrl = datasourceUrl;
        this.username = username;
        this.password = password;
        this.tableName = tableName;
        this.dimension = dimension;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    public String datasourceUrl() {
        return datasourceUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String tableName() {
        return tableName;
    }

    public int dimension() {
        return dimension;
    }

    // --- Métodos "add" sem metadados: não são usados neste projeto ---
    // O contrato EmbeddingStore original permite adicionar um embedding
    // "puro", sem texto/metadados associados. Como aqui SEMPRE queremos
    // guardar de qual arquivo/página o chunk veio, essas variantes são
    // desabilitadas de propósito para forçar o uso de add(Embedding, TextSegment).

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("Use add(Embedding, TextSegment) to preserve metadata");
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException("Use add(Embedding, TextSegment) to preserve metadata");
    }

    /**
     * Insere um chunk de documento (texto + metadados) junto com seu
     * embedding no banco. É chamado uma vez por chunk durante a
     * ingestão (ver {@code DocumentIngestionServiceImpl}).
     *
     * <p>O vetor é convertido para o formato textual que o Postgres
     * entende (ex: {@code [0.12,0.87,...]}) e inserido via
     * {@code CAST(? AS vector)}, que converte esse texto para o tipo
     * nativo {@code vector} da extensão pgvector.</p>
     */
    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        Metadata metadata = embedded.metadata() == null ? new Metadata() : embedded.metadata();
        jdbcTemplate.update(
                "INSERT INTO " + tableName + " (id, content, embedding, source_file, page_number, chunk_hash) "
                        + "VALUES (?, ?, CAST(? AS vector), ?, ?, ?)",
                UUID.fromString(id),
                embedded.text(),
                vectorToSqlLiteral(embedding),
                metadata.getString(SOURCE_FILE),
                metadata.getInteger(PAGE_NUMBER),
                metadata.getString(CHUNK_HASH));
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException("Use addAll with embedded segments");
    }

    /** Insere vários chunks em sequência, reaproveitando {@link #add}. */
    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        for (int i = 0; i < embeddings.size(); i++) {
            add(embeddings.get(i), embedded.get(i));
        }
    }

    @Override
    public void removeAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM " + tableName + " WHERE id IN (" + placeholders + ")", ids.toArray());
    }

    @Override
    public void removeAll(Filter filter) {
        throw new UnsupportedOperationException("Filtering delete is not supported");
    }

    /** Apaga todos os chunks — usado principalmente nos testes, entre um teste e outro. */
    @Override
    public void removeAll() {
        jdbcTemplate.update("DELETE FROM " + tableName);
    }

    /**
     * O coração da busca RAG: dado o vetor da pergunta do usuário
     * ({@code request.queryEmbedding()}), busca no Postgres os chunks
     * mais parecidos semanticamente.
     *
     * <p>SQL explicado:</p>
     * <ul>
     *   <li>{@code embedding <=> vetor} — operador do pgvector que
     *       calcula a distância de cosseno entre dois vetores (0 = idênticos,
     *       2 = opostos).</li>
     *   <li>{@code 1 - (embedding <=> vetor) AS similarity} — converte
     *       distância em "similaridade" (1 = idêntico, quanto menor mais
     *       diferente), mais intuitivo para o restante do código.</li>
     *   <li>{@code WHERE similarity >= minScore} — descarta chunks
     *       fracamente relacionados (o "limiar" configurado em
     *       {@code rag.retrieval.min-similarity}, default 0.5). É essa
     *       cláusula que faz o sistema dizer "não sei" quando a pergunta
     *       não tem relação com os documentos ingeridos.</li>
     *   <li>{@code ORDER BY ... ASC LIMIT maxResults} — pega os N chunks
     *       mais próximos (menor distância = mais similar), onde N é o
     *       "top-K" configurado (default 4).</li>
     * </ul>
     */
    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (request.queryEmbedding() == null) {
            throw new IllegalArgumentException("queryEmbedding is required");
        }
        String vector = vectorToSqlLiteral(request.queryEmbedding());
        List<EmbeddingMatch<TextSegment>> matches = jdbcTemplate.query(
                "SELECT id, content, source_file, page_number, chunk_hash, "
                        + "1 - (embedding <=> CAST(? AS vector)) AS similarity "
                        + "FROM " + tableName + " "
                        + "WHERE 1 - (embedding <=> CAST(? AS vector)) >= ? "
                        + "ORDER BY embedding <=> CAST(? AS vector) ASC "
                        + "LIMIT ?",
                (rs, rowNum) -> toMatch(rs),
                vector,
                vector,
                request.minScore(),
                vector,
                request.maxResults());
        return new EmbeddingSearchResult<>(matches);
    }

    /** Converte uma linha do ResultSet de volta em um objeto de domínio do LangChain4j. */
    private EmbeddingMatch<TextSegment> toMatch(ResultSet rs) throws SQLException {
        Metadata metadata = Metadata.from(Map.of(
                SOURCE_FILE, rs.getString("source_file"),
                CHUNK_HASH, rs.getString("chunk_hash")));
        Integer pageNumber = (Integer) rs.getObject("page_number");
        if (pageNumber != null) {
            metadata.put(PAGE_NUMBER, pageNumber);
        }
        TextSegment segment = TextSegment.from(rs.getString("content"), metadata);
        return new EmbeddingMatch<>(rs.getDouble("similarity"), rs.getString("id"), null, segment);
    }

    /**
     * Converte o vetor de floats do Java (ex: {@code [0.12f, 0.87f, ...]})
     * para a representação textual que o Postgres/pgvector entende
     * (ex: a string {@code "[0.12,0.87,...]"}), usada dentro do
     * {@code CAST(? AS vector)} nas queries SQL acima.
     */
    private String vectorToSqlLiteral(Embedding embedding) {
        float[] vector = embedding.vector();
        if (vector.length != dimension) {
            log.debug("Embedding dimension {} differs from configured {}", vector.length, dimension);
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }
}
