package com.example.ragmcp.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class LangChain4jConfig {

    @Bean
    ChatModel chatModel(
            @Value("${rag.ollama.base-url}") String baseUrl,
            @Value("${rag.ollama.chat-model}") String chatModel) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(chatModel)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(1)
                .build();
    }

    @Bean
    EmbeddingModel embeddingModel(
            @Value("${rag.ollama.base-url}") String baseUrl,
            @Value("${rag.ollama.embedding-model}") String embeddingModel) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(embeddingModel)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(1)
                .build();
    }

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            @Value("${spring.datasource.url}") String datasourceUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        return new JdbcPgVectorEmbeddingStore(dataSource, jdbcTemplate, datasourceUrl, username, password,
                "document_chunks", 768);
    }
}
