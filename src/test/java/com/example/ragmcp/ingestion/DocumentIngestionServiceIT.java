package com.example.ragmcp.ingestion;

import com.example.ragmcp.testsupport.AbstractPgVectorIntegrationTest;
import com.example.ragmcp.testsupport.TestFixtures;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIngestionServiceIT extends AbstractPgVectorIntegrationTest {

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Test
    void shouldCreatePdfChunksWith768DimEmbeddings() {
        // CA01 spec 01
        IngestionResult result = documentIngestionService.ingestFile(TestFixtures.samplePdf());

        assertThat(result.success()).isTrue();
        assertThat(result.chunksCreated()).isGreaterThan(0);
        List<Integer> dimensions = jdbcTemplate.query(
                "SELECT vector_dims(embedding) FROM document_chunks ORDER BY created_at",
                (rs, rowNum) -> rs.getInt(1));
        assertThat(dimensions).isNotEmpty().allMatch(dimension -> dimension == 768);
    }

    @Test
    void shouldBeIdempotentWhenReingestingSameFile() {
        // CA02 spec 01
        IngestionResult first = documentIngestionService.ingestFile(TestFixtures.samplePdf());
        IngestionResult second = documentIngestionService.ingestFile(TestFixtures.samplePdf());

        assertThat(first.success()).isTrue();
        assertThat(first.chunksCreated()).isGreaterThan(0);
        assertThat(second.success()).isTrue();
        assertThat(second.chunksCreated()).isZero();
        assertThat(second.chunksSkippedAsDuplicate()).isEqualTo(first.chunksCreated());
    }

    @Test
    void shouldContinueBatchIngestionWhenOneFileIsCorrupted() {
        // CA03 spec 01
        Path batchDirectory = TestFixtures.createBatchDirectory();

        List<IngestionResult> results = documentIngestionService.ingestDirectory(batchDirectory);

        assertThat(results).hasSize(3);
        assertThat(results).filteredOn(IngestionResult::success).hasSize(2);
        assertThat(results).filteredOn(result -> !result.success()).singleElement()
                .extracting(IngestionResult::fileName)
                .isEqualTo("corrupted.pdf");
    }

    @Test
    void shouldPersistSourceFileAndPageNumberMetadata() {
        // CA04 spec 01
        documentIngestionService.ingestFile(TestFixtures.samplePdf());

        List<ChunkMetadataRow> rows = jdbcTemplate.query(
                "SELECT source_file, page_number FROM document_chunks ORDER BY page_number",
                (rs, rowNum) -> new ChunkMetadataRow(rs.getString(1), (Integer) rs.getObject(2)));

        assertThat(rows).isNotEmpty();
        assertThat(rows).allMatch(row -> "sample.pdf".equals(row.sourceFile()));
        assertThat(rows).extracting(ChunkMetadataRow::pageNumber).contains(1, 2, 3);
    }

    private record ChunkMetadataRow(String sourceFile, Integer pageNumber) {
    }
}
