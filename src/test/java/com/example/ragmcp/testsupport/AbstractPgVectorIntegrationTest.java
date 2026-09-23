package com.example.ragmcp.testsupport;

import com.example.ragmcp.RagMcpApplication;
import com.example.ragmcp.config.JdbcPgVectorEmbeddingStore;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = RagMcpApplication.class)
@Import(FakeModelsTestConfiguration.class)
public abstract class AbstractPgVectorIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = createContainer();

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JdbcPgVectorEmbeddingStore embeddingStore;

    @Autowired
    protected DeterministicEmbeddingModel deterministicEmbeddingModel;

    @Autowired
    protected DeterministicChatModel deterministicChatModel;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("rag.ollama.base-url", () -> "http://127.0.0.1:9");
        registry.add("mcp.server.stdio-enabled", () -> "false");
        registry.add("mcp.client.config-file", () ->
                Path.of(System.getProperty("user.dir"), "target", "test-fixtures", "empty-mcp-config.json").toString());
    }

    @BeforeEach
    void resetState() {
        embeddingStore.removeAll();
        deterministicChatModel.clear();
    }

    private static PostgreSQLContainer<?> createContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("ragmcp")
                .withUsername("ragmcp")
                .withPassword("ragmcp");
        container.start();
        return container;
    }
}
