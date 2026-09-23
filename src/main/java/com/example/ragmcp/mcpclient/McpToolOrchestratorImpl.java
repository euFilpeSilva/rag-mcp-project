package com.example.ragmcp.mcpclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class McpToolOrchestratorImpl implements McpToolOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(McpToolOrchestratorImpl.class);
    private static final String SYSTEM_PROMPT = """
            Você decide se deve usar uma ferramenta MCP externa para responder.
            Use uma ferramenta apenas quando ela ajudar diretamente a responder à pergunta.
            Se nenhuma ferramenta for necessária, não chame nenhuma.
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final Path configFile;
    private final List<McpClient> clients = new ArrayList<>();
    private final Map<String, String> toolToServer = new ConcurrentHashMap<>();

    public McpToolOrchestratorImpl(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            @Value("${mcp.client.config-file}") String configFile) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.configFile = Path.of(configFile);
    }

    @PostConstruct
    void initialize() {
        if (!Files.exists(configFile)) {
            log.info("Arquivo de configuração MCP não encontrado: {}", configFile);
            return;
        }
        McpServersConfig config = readConfig();
        for (McpServerConfig server : config.servers()) {
            try {
                McpClient client = buildClient(server);
                client.listTools().forEach(tool -> toolToServer.put(tool.name(), server.name()));
                clients.add(client);
            } catch (Exception e) {
                log.warn("Falha ao conectar ao servidor MCP externo {}", server.name(), e);
            }
        }
    }

    @PreDestroy
    void closeClients() {
        for (McpClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Falha ao fechar cliente MCP {}", client.key(), e);
            }
        }
    }

    @Override
    public FallbackResult tryFallback(String question) {
        if (clients.isEmpty()) {
            return new FallbackResult(false, null, null, null);
        }

        McpToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(clients)
                .failIfOneServerFails(false)
                .build();

        ToolProviderResult providedTools = toolProvider.provideTools(
                new ToolProviderRequest(null, UserMessage.from(question)));
        if (providedTools.tools().isEmpty()) {
            return new FallbackResult(false, null, null, null);
        }

        List<ToolSpecification> toolSpecifications = new ArrayList<>(providedTools.tools().keySet());
        ChatRequest firstRequest = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(SYSTEM_PROMPT),
                        UserMessage.from(question)))
                .toolSpecifications(toolSpecifications)
                .build();

        var firstResponse = chatModel.chat(firstRequest).aiMessage();
        if (!firstResponse.hasToolExecutionRequests()) {
            return new FallbackResult(false, null, null, null);
        }

        ToolExecutionRequest toolRequest = firstResponse.toolExecutionRequests().getFirst();
        ToolExecutor executor = providedTools.toolExecutorByName(toolRequest.name());
        if (executor == null) {
            return new FallbackResult(false, null, null, null);
        }

        String rawToolOutput;
        try {
            rawToolOutput = executor.execute(toolRequest, null);
        } catch (Exception e) {
            String serverName = toolToServer.getOrDefault(toolRequest.name(), toolRequest.name());
            return new FallbackResult(
                    true,
                    toolRequest.name(),
                    "[Fonte externa: " + serverName + "] Não foi possível concluir o fallback externo em tempo hábil.",
                    e.getMessage());
        }

        ChatRequest secondRequest = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(SYSTEM_PROMPT),
                        UserMessage.from(question),
                        firstResponse,
                        ToolExecutionResultMessage.from(toolRequest, rawToolOutput)))
                .build();

        String finalAnswer = chatModel.chat(secondRequest).aiMessage().text();
        String serverName = toolToServer.getOrDefault(toolRequest.name(), toolRequest.name());
        return new FallbackResult(
                true,
                toolRequest.name(),
                "[Fonte externa: " + serverName + "] " + finalAnswer,
                rawToolOutput);
    }

    McpClient buildClient(McpServerConfig server) {
        List<String> command = new ArrayList<>();
        command.add(server.command());
        command.addAll(server.args());

        StdioMcpTransport transport = StdioMcpTransport.builder()
                .command(command)
                .logEvents(false)
                .build();

        McpClient client = DefaultMcpClient.builder()
                .key(server.name())
                .clientName("rag-mcp-project")
                .clientVersion("0.1.0")
                .transport(transport)
                .initializationTimeout(Duration.ofSeconds(10))
                .protocolDetectionTimeout(Duration.ofSeconds(10))
                .toolExecutionTimeout(Duration.ofSeconds(10))
                .resourcesTimeout(Duration.ofSeconds(10))
                .promptsTimeout(Duration.ofSeconds(10))
                .build();

        client.listTools();
        return client;
    }

    McpServersConfig readConfig() {
        try {
            String json = Files.readString(configFile);
            return objectMapper.readValue(json, McpServersConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler configuração MCP: " + configFile, e);
        }
    }

    public record McpServersConfig(List<McpServerConfig> servers) {
        public McpServersConfig {
            servers = servers == null ? List.of() : List.copyOf(servers);
        }
    }

    public record McpServerConfig(String name, String command, List<String> args) {
        public McpServerConfig {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }
}
