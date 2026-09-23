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

/**
 * Implementação do fluxo de <b>fallback</b> via MCP (spec 04): quando o
 * RAG local não tem contexto suficiente para responder, esta classe
 * tenta usar ferramentas MCP <b>externas</b> (servidores configurados em
 * {@code mcp-servers-config.json}, ex: um servidor de filesystem) para
 * ainda assim tentar responder à pergunta.
 *
 * <p>Fluxo de {@link #tryFallback(String)}:</p>
 * <ol>
 *   <li>Se não há nenhum servidor MCP externo configurado/conectado,
 *       desiste imediatamente (fallback não usado).</li>
 *   <li>Pergunta ao {@code ChatModel} (LLM) se, dadas as ferramentas
 *       externas disponíveis, alguma deveria ser chamada para responder
 *       — é o próprio LLM que decide (via "tool calling" / function
 *       calling do LangChain4j).</li>
 *   <li>Se o LLM pedir para chamar uma ferramenta, executa essa
 *       ferramenta de fato (ex: ler um arquivo do sistema).</li>
 *   <li>Manda o resultado da ferramenta de volta para o LLM, para que
 *       ele formule a resposta final em linguagem natural, já citando
 *       de qual servidor externo veio a informação.</li>
 * </ol>
 */
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
    /** Um {@code McpClient} por servidor externo configurado (conexão já estabelecida). */
    private final List<McpClient> clients = new ArrayList<>();
    /** Mapeia nome da ferramenta -> nome do servidor que a oferece (para citar a fonte na resposta). */
    private final Map<String, String> toolToServer = new ConcurrentHashMap<>();

    public McpToolOrchestratorImpl(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            @Value("${mcp.client.config-file}") String configFile) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.configFile = Path.of(configFile);
    }

    /**
     * Lê {@code mcp-servers-config.json} na inicialização e conecta em
     * cada servidor MCP externo declarado ali. Se o arquivo não existir,
     * ou se algum servidor falhar ao conectar, o sistema continua
     * funcionando normalmente — apenas sem esse fallback disponível.
     */
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

    /** Encerra as conexões com os servidores MCP externos ao desligar a aplicação. */
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

    /**
     * Tenta responder a pergunta usando ferramentas MCP externas. Veja a
     * documentação da classe para o passo a passo completo.
     */
    @Override
    public FallbackResult tryFallback(String question) {
        if (clients.isEmpty()) {
            return new FallbackResult(false, null, null, null);
        }

        // Descobre quais ferramentas dos servidores conectados fazem
        // sentido oferecer ao LLM para esta pergunta específica.
        McpToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(clients)
                .failIfOneServerFails(false)
                .build();

        ToolProviderResult providedTools = toolProvider.provideTools(
                new ToolProviderRequest(null, UserMessage.from(question)));
        if (providedTools.tools().isEmpty()) {
            return new FallbackResult(false, null, null, null);
        }

        // 1ª chamada ao LLM: ele recebe a lista de ferramentas disponíveis
        // e decide (ou não) pedir para executar uma delas.
        List<ToolSpecification> toolSpecifications = new ArrayList<>(providedTools.tools().keySet());
        ChatRequest firstRequest = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from(SYSTEM_PROMPT),
                        UserMessage.from(question)))
                .toolSpecifications(toolSpecifications)
                .build();

        var firstResponse = chatModel.chat(firstRequest).aiMessage();
        if (!firstResponse.hasToolExecutionRequests()) {
            // O LLM decidiu que nenhuma ferramenta externa era necessária.
            return new FallbackResult(false, null, null, null);
        }

        // Executa de fato a ferramenta que o LLM pediu (ex: ler um arquivo).
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

        // 2ª chamada ao LLM: agora com o resultado da ferramenta em mãos,
        // pede para ele formular a resposta final em linguagem natural.
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

    /**
     * Constrói e conecta um {@code McpClient} a um servidor MCP externo,
     * lançando o processo declarado em {@code command}/{@code args} (ex:
     * {@code npx -y @modelcontextprotocol/server-filesystem /pasta}) e
     * comunicando com ele via stdio — o mesmo mecanismo de transporte
     * que este projeto usa do lado servidor (ver {@code McpStdioServerRunner}).
     */
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

    /** Lê e desserializa {@code mcp-servers-config.json} para a lista de servidores configurados. */
    McpServersConfig readConfig() {
        try {
            String json = Files.readString(configFile);
            return objectMapper.readValue(json, McpServersConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler configuração MCP: " + configFile, e);
        }
    }

    /** Estrutura do JSON de configuração: {@code { "servers": [ ... ] } }. */
    public record McpServersConfig(List<McpServerConfig> servers) {
        public McpServersConfig {
            servers = servers == null ? List.of() : List.copyOf(servers);
        }
    }

    /** Um servidor MCP externo: como iniciá-lo (comando + argumentos) e seu nome de exibição. */
    public record McpServerConfig(String name, String command, List<String> args) {
        public McpServerConfig {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }
}
