package com.example.ragmcp.testsupport;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration
public class FakeModelsTestConfiguration {

    @Bean
    @Primary
    public DeterministicEmbeddingModel deterministicEmbeddingModel() {
        return new DeterministicEmbeddingModel();
    }

    @Bean
    @Primary
    public DeterministicChatModel deterministicChatModel() {
        return new DeterministicChatModel();
    }
}
