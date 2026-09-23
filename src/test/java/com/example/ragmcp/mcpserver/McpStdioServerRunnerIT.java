package com.example.ragmcp.mcpserver;

import com.example.ragmcp.ingestion.DocumentIngestionService;
import com.example.ragmcp.rag.RagAnswer;
import com.example.ragmcp.rag.RagQueryService;
import com.example.ragmcp.rag.SourceReference;
import com.example.ragmcp.testsupport.AbstractPgVectorIntegrationTest;
import com.example.ragmcp.testsupport.TestFixtures;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class McpStdioServerRunnerIT extends AbstractPgVectorIntegrationTest {

    @Autowired
    private McpStdioServerRunner runner;

    @Autowired
    private RagQueryService ragQueryService;

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @BeforeEach
    void ingestFixtures() {
        documentIngestionService.ingestFile(TestFixtures.samplePdf());
    }

    @Test
    void shouldExposeTheThreeSpecifiedTools() {
        // CA01 spec 03
        McpSchema.Tool askTool = ReflectionTestUtils.invokeMethod(runner, "askQuestionTool");
        McpSchema.Tool searchTool = ReflectionTestUtils.invokeMethod(runner, "searchDocumentsTool");
        McpSchema.Tool ingestTool = ReflectionTestUtils.invokeMethod(runner, "ingestDocumentTool");

        assertThat(List.of(askTool.name(), searchTool.name(), ingestTool.name()))
                .containsExactly("ask_question", "search_documents", "ingest_document");
        assertThat(askTool.inputSchema()).containsEntry("type", "object");
        assertThat(searchTool.inputSchema()).containsKey("properties");
        assertThat(ingestTool.inputSchema()).containsKey("required");
    }

    @Test
    void shouldReturnSameAnswerAsDirectRagServiceCall() throws Exception {
        // CA02 spec 03
        RagAnswer directAnswer = ragQueryService.ask("mcp-ca02-direct", "Qual e a garantia do produto X?");
        McpSchema.CallToolResult result = executeTool("ask_question", Map.of(
                "question", "Qual e a garantia do produto X?",
                "sessionId", "mcp-ca02-mcp"), "handleAskQuestion");

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isEqualTo(directAnswer);
    }

    @Test
    void shouldReturnToolErrorForMissingFileWithoutCrashing() throws Exception {
        // CA03 spec 03
        McpSchema.CallToolResult result = executeTool("ingest_document", Map.of(
                "filePath", Path.of("target", "test-fixtures", "missing.pdf").toString()), "handleIngestDocument");

        assertThat(result.isError()).isTrue();
        assertThat(result.structuredContent()).isNotNull();
        assertThat(result.content().toString()).contains("Arquivo inválido");
    }

    @Test
    void shouldReturnSearchResultsOrderedByDescendingSimilarity() {
        // CA04 spec 03
        @SuppressWarnings("unchecked")
        List<SourceReference> results = ReflectionTestUtils.invokeMethod(
                runner, "handleSearchDocuments", Map.of("query", "servidor MCP", "topK", 4));

        assertThat(results).isNotEmpty();
        assertThat(results).isSortedAccordingTo((left, right) ->
                Double.compare(right.similarityScore(), left.similarityScore()));
    }

    private McpSchema.CallToolResult executeTool(String toolName, Map<String, Object> arguments, String delegateMethod)
            throws Exception {
        Class<?> toolHandlerType = Class.forName(McpStdioServerRunner.class.getName() + "$ToolHandler");
        Object proxy = Proxy.newProxyInstance(
                toolHandlerType.getClassLoader(),
                new Class<?>[]{toolHandlerType},
                (ignored, method, args) -> ReflectionTestUtils.invokeMethod(runner, delegateMethod, args[0]));
        Method executeTool = McpStdioServerRunner.class.getDeclaredMethod(
                "executeTool", String.class, Map.class, toolHandlerType);
        executeTool.setAccessible(true);
        return (McpSchema.CallToolResult) executeTool.invoke(runner, toolName, arguments, proxy);
    }
}
