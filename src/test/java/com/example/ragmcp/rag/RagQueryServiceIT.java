package com.example.ragmcp.rag;

import com.example.ragmcp.ingestion.DocumentIngestionService;
import com.example.ragmcp.testsupport.AbstractPgVectorIntegrationTest;
import com.example.ragmcp.testsupport.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class RagQueryServiceIT extends AbstractPgVectorIntegrationTest {

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Autowired
    private RagQueryService ragQueryService;

    @BeforeEach
    void ingestFixtures() {
        documentIngestionService.ingestFile(TestFixtures.samplePdf());
        documentIngestionService.ingestFile(TestFixtures.sampleTxt());
    }

    @Test
    void shouldAnswerUsingLocalDocumentsAndReturnSources() {
        // CA01 spec 02
        RagAnswer answer = ragQueryService.ask("session-ca01", "Qual e a garantia do produto X?");

        assertThat(answer.insufficientContext()).isFalse();
        assertThat(answer.answer()).contains("12 meses");
        assertThat(answer.sources()).isNotEmpty();
    }

    @Test
    void shouldFlagInsufficientContextForUnrelatedQuestions() {
        // CA02 spec 02
        RagAnswer answer = ragQueryService.ask("session-ca02", "Qual e a capital da Franca?");

        assertThat(answer.insufficientContext()).isTrue();
        assertThat(answer.answer()).containsIgnoringCase("contexto suficiente");
        assertThat(answer.sources()).isEmpty();
    }

    @Test
    void shouldRejectGeneratedAnswerWhenItAddsUnsupportedFacts() {
        deterministicChatModel.forceResponse("A garantia do produto X e de 24 meses.");

        RagAnswer answer = ragQueryService.ask("session-unsupported-answer", "Qual e a garantia do produto X?");

        assertThat(answer.insufficientContext()).isTrue();
        assertThat(answer.answer()).containsIgnoringCase("contexto suficiente");
        assertThat(answer.sources()).isEmpty();
    }

    @Test
    void shouldPreserveSessionHistoryAcrossFollowUpQuestions() {
        // CA03 spec 02
        RagAnswer first = ragQueryService.ask("session-ca03", "Qual e a garantia do produto X?");
        RagAnswer second = ragQueryService.ask("session-ca03", "E sobre o capitulo 2?");

        assertThat(first.answer()).contains("12 meses");
        assertThat(second.answer()).contains("220V");
        assertThat(deterministicChatModel.recordedRequests()).hasSize(2);
        int firstMessageCount = deterministicChatModel.recordedRequests().get(0).messages().size();
        int secondMessageCount = deterministicChatModel.recordedRequests().get(1).messages().size();
        assertThat(secondMessageCount).isGreaterThan(firstMessageCount);
    }

    @Test
    void shouldReturnSourcesMatchingHighestSimilarityChunks() {
        // CA04 spec 02
        String question = "Como funciona o servidor MCP?";
        RagAnswer answer = ragQueryService.ask("session-ca04", question);

        String queryVector = deterministicEmbeddingModel.toSqlVectorLiteral(question);
        List<SourceReference> expectedTopSources = jdbcTemplate.query(
                "SELECT source_file, page_number, 1 - (embedding <=> CAST(? AS vector)) AS similarity "
                        + "FROM document_chunks "
                        + "WHERE 1 - (embedding <=> CAST(? AS vector)) >= 0.5 "
                        + "ORDER BY embedding <=> CAST(? AS vector) ASC "
                        + "LIMIT 4",
                (rs, rowNum) -> new SourceReference(
                        rs.getString("source_file"),
                        (Integer) rs.getObject("page_number"),
                        rs.getDouble("similarity")),
                queryVector,
                queryVector,
                queryVector);

        assertThat(answer.sources()).hasSize(expectedTopSources.size());
        for (int i = 0; i < expectedTopSources.size(); i++) {
            SourceReference actual = answer.sources().get(i);
            SourceReference expected = expectedTopSources.get(i);
            assertThat(actual.fileName()).isEqualTo(expected.fileName());
            assertThat(actual.pageNumber()).isEqualTo(expected.pageNumber());
            assertThat(actual.similarityScore()).isCloseTo(expected.similarityScore(), org.assertj.core.data.Offset.offset(1.0E-6));
        }
    }
}
