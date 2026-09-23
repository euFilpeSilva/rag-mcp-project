package com.example.ragmcp.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public final class TestFixtures {

    private static final Path FIXTURES_DIR =
            Path.of(System.getProperty("user.dir"), "src", "test", "resources", "fixtures");
    private static final Path TARGET_FIXTURES_DIR =
            Path.of(System.getProperty("user.dir"), "target", "test-fixtures");

    private TestFixtures() {
    }

    public static Path samplePdf() {
        Path pdf = FIXTURES_DIR.resolve("sample.pdf");
        try {
            Files.createDirectories(FIXTURES_DIR);
            Files.deleteIfExists(pdf);
            try (PDDocument document = new PDDocument()) {
                for (String pageText : samplePdfPages()) {
                    PDPage page = new PDPage();
                    document.addPage(page);
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.newLineAtOffset(72, 720);
                        stream.showText(pageText);
                        stream.endText();
                    }
                }
                document.save(pdf.toFile());
            }
            return pdf;
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao criar fixture sample.pdf", e);
        }
    }

    public static Path sampleTxt() {
        return FIXTURES_DIR.resolve("sample.txt");
    }

    public static Path corruptedPdf() {
        return FIXTURES_DIR.resolve("corrupted.pdf");
    }

    public static Path createBatchDirectory() {
        try {
            Files.createDirectories(TARGET_FIXTURES_DIR);
            Path batchDir = TARGET_FIXTURES_DIR.resolve("batch-" + UUID.randomUUID());
            Files.createDirectories(batchDir);
            Files.copy(samplePdf(), batchDir.resolve("sample.pdf"));
            Files.copy(sampleTxt(), batchDir.resolve("sample.txt"));
            Files.copy(corruptedPdf(), batchDir.resolve("corrupted.pdf"));
            return batchDir;
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao criar diretório de fixtures em lote", e);
        }
    }

    public static byte[] samplePdfBytes() {
        try {
            return Files.readAllBytes(samplePdf());
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler fixture sample.pdf", e);
        }
    }

    public static Path writeConfigFile(String fileName, String content) {
        try {
            Files.createDirectories(TARGET_FIXTURES_DIR);
            Path file = TARGET_FIXTURES_DIR.resolve(fileName);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao escrever arquivo de configuração de teste", e);
        }
    }

    private static List<String> samplePdfPages() {
        return List.of(
                "Garantia do produto: garantia oficial de 12 meses. Garantia de 12 meses para defeitos de fabricacao.",
                "Capitulo 2: capitulo 2 instalacao 220V. A instalacao 220V requer rede eletrica 220V e cabo aterrado.",
                "Servidor MCP: servidor MCP expoe busca, ingestao e perguntas. Ferramentas MCP locais.");
    }
}
