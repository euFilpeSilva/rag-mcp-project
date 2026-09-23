package com.example.ragmcp.mcpclient;

import com.example.ragmcp.testsupport.DeterministicChatModel;
import com.example.ragmcp.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpClient;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolOrchestratorIT {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSafelyReturnUnusedFallbackWhenNoServersAreConfigured() {
        // CA02 spec 04
        Path configFile = TestFixtures.writeConfigFile("mcp-empty.json", "{\"servers\":[]}");
        McpToolOrchestratorImpl orchestrator =
                new McpToolOrchestratorImpl(new DeterministicChatModel(), objectMapper, configFile.toString());

        orchestrator.initialize();
        FallbackResult result = orchestrator.tryFallback("pergunta sem servidores");
        orchestrator.closeClients();

        assertThat(result.used()).isFalse();
        assertThat(result.toolName()).isNull();
        assertThat(result.answer()).isNull();
    }

    @Test
    void shouldIgnoreBrokenServerAndKeepOtherClientsAvailable() throws Exception {
        // CA03 spec 04
        Path configFile = TestFixtures.writeConfigFile(
                "mcp-broken-and-good.json",
                """
                {
                  "servers": [
                    {"name":"broken","command":"broken","args":[]},
                    {"name":"good","command":"good","args":[]}
                  ]
                }
                """);

        McpClient goodClient = Mockito.mock(McpClient.class);
        Mockito.when(goodClient.key()).thenReturn("good");
        Mockito.when(goodClient.listTools()).thenReturn(List.of());

        TestableMcpToolOrchestrator orchestrator = new TestableMcpToolOrchestrator(
                new DeterministicChatModel(), objectMapper, configFile.toString(), goodClient);

        orchestrator.initialize();
        FallbackResult result = orchestrator.tryFallback("pergunta sem ferramentas");
        orchestrator.closeClients();

        List<?> clients = readClients(orchestrator);
        assertThat(clients).hasSize(1);
        assertThat(result.used()).isFalse();
    }

    private List<?> readClients(McpToolOrchestratorImpl orchestrator) throws Exception {
        Field clientsField = McpToolOrchestratorImpl.class.getDeclaredField("clients");
        clientsField.setAccessible(true);
        return (List<?>) clientsField.get(orchestrator);
    }

    private static final class TestableMcpToolOrchestrator extends McpToolOrchestratorImpl {

        private final McpClient goodClient;

        private TestableMcpToolOrchestrator(
                DeterministicChatModel chatModel,
                ObjectMapper objectMapper,
                String configFile,
                McpClient goodClient) {
            super(chatModel, objectMapper, configFile);
            this.goodClient = goodClient;
        }

        @Override
        McpClient buildClient(McpServerConfig server) {
            if ("broken".equals(server.name())) {
                throw new IllegalStateException("falha simulada");
            }
            return goodClient;
        }
    }
}
