package com.example.ragmcp.mcpserver;

import com.example.ragmcp.ingestion.DocumentIngestionService;
import com.example.ragmcp.ingestion.IngestionResult;
import com.example.ragmcp.rag.RagQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inicia o servidor MCP via stdio apenas quando a flag {@code mcp.server.stdio-enabled}
 * estiver habilitada. Em modo REST normal ela deve permanecer desabilitada para evitar
 * interferência no stdout, reservado ao protocolo MCP.
 */
@Component
public class McpStdioServerRunner {

    private static final Logger log = LoggerFactory.getLogger(McpStdioServerRunner.class);

    private final boolean stdioEnabled;
    private final RagQueryService ragQueryService;
    private final DocumentIngestionService documentIngestionService;
    private final ObjectMapper objectMapper;
    private McpSyncServer server;

    public McpStdioServerRunner(
            @Value("${mcp.server.stdio-enabled:false}") boolean stdioEnabled,
            RagQueryService ragQueryService,
            DocumentIngestionService documentIngestionService,
            ObjectMapper objectMapper) {
        this.stdioEnabled = stdioEnabled;
        this.ragQueryService = ragQueryService;
        this.documentIngestionService = documentIngestionService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void startIfEnabled() {
        if (!stdioEnabled) {
            return;
        }

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        StdioServerTransportProvider transportProvider =
                new StdioServerTransportProvider(jsonMapper, System.in, System.out);

        server = McpServer.sync(transportProvider)
                .serverInfo("rag-mcp-project", "0.1.0")
                .instructions("Ferramentas RAG locais para documentos PDF/TXT")
                .jsonMapper(jsonMapper)
                .requestTimeout(Duration.ofSeconds(30))
                .tools(
                        syncTool(askQuestionTool(), this::handleAskQuestion),
                        syncTool(searchDocumentsTool(), this::handleSearchDocuments),
                        syncTool(ingestDocumentTool(), this::handleIngestDocument))
                .build();

        System.err.println("MCP stdio server iniciado com 3 ferramentas.");
    }

    @PreDestroy
    void shutdown() {
        if (server != null) {
            server.closeGracefully();
            server.close();
        }
    }

    private McpServerFeatures.SyncToolSpecification syncTool(
            McpSchema.Tool tool,
            ToolHandler handler) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> executeTool(tool.name(), request.arguments(), handler))
                .build();
    }

    private McpSchema.CallToolResult executeTool(
            String toolName,
            Map<String, Object> arguments,
            ToolHandler handler) {
        Instant startedAt = Instant.now();
        try {
            System.err.println("MCP tool call start name=" + toolName + " args=" + arguments);
            Object result = handler.handle(arguments);
            if (result instanceof IngestionResult ingestionResult && !ingestionResult.success()) {
                return McpSchema.CallToolResult.builder(List.of(new McpSchema.TextContent(ingestionResult.errorMessage())))
                        .structuredContent(result)
                        .isError(true)
                        .build();
            }
            String payload = objectMapper.writeValueAsString(result);
            System.err.println("MCP tool call end name=" + toolName + " durationMs="
                    + Duration.between(startedAt, Instant.now()).toMillis());
            return McpSchema.CallToolResult.builder(List.of(new McpSchema.TextContent(payload)))
                    .structuredContent(result)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            log.warn("Falha na ferramenta MCP {}", toolName, e);
            System.err.println("MCP tool call error name=" + toolName + " durationMs="
                    + Duration.between(startedAt, Instant.now()).toMillis() + " message=" + e.getMessage());
            return McpSchema.CallToolResult.builder(List.of(new McpSchema.TextContent(e.getMessage())))
                    .isError(true)
                    .build();
        }
    }

    private Object handleAskQuestion(Map<String, Object> arguments) {
        String question = requiredString(arguments, "question");
        String sessionId = string(arguments, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "mcp-session";
        }
        return ragQueryService.ask(sessionId, question);
    }

    private Object handleSearchDocuments(Map<String, Object> arguments) {
        String query = requiredString(arguments, "query");
        int topK = integer(arguments, "topK", 4);
        return ragQueryService.search(query, topK);
    }

    private Object handleIngestDocument(Map<String, Object> arguments) {
        String filePath = requiredString(arguments, "filePath");
        return documentIngestionService.ingestFile(Path.of(filePath));
    }

    private McpSchema.Tool askQuestionTool() {
        return McpSchema.Tool.builder()
                .name("ask_question")
                .description("Responde a uma pergunta usando RAG sobre os documentos locais ingeridos.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "question", Map.of("type", "string"),
                                "sessionId", Map.of("type", "string")),
                        "required", List.of("question")))
                .build();
    }

    private McpSchema.Tool searchDocumentsTool() {
        return McpSchema.Tool.builder()
                .name("search_documents")
                .description("Busca trechos de documentos relevantes para uma consulta, sem gerar resposta via LLM.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string"),
                                "topK", Map.of("type", "integer", "default", 4)),
                        "required", List.of("query")))
                .build();
    }

    private McpSchema.Tool ingestDocumentTool() {
        return McpSchema.Tool.builder()
                .name("ingest_document")
                .description("Ingere um novo arquivo PDF ou TXT na base vetorial.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("filePath", Map.of("type", "string")),
                        "required", List.of("filePath")))
                .build();
    }

    private String requiredString(Map<String, Object> arguments, String key) {
        String value = string(arguments, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Parâmetro obrigatório ausente: " + key);
        }
        return value;
    }

    private String string(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : value.toString();
    }

    private int integer(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    @FunctionalInterface
    private interface ToolHandler {
        Object handle(Map<String, Object> arguments) throws IOException;
    }
}
