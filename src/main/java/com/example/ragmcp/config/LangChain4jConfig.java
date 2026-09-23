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

/**
 * Classe de configuração Spring: define os "beans" (objetos gerenciados
 * pelo container do Spring) que conectam a aplicação às peças externas
 * do RAG — o LLM/embeddings (Ollama) e o banco vetorial (PGVector).
 *
 * <p>Um "bean" aqui é simplesmente um objeto criado uma única vez pelo
 * Spring e reaproveitado (injetado) em qualquer outra classe que precise
 * dele (ex: {@code RagQueryServiceImpl} recebe {@code ChatModel} e
 * {@code EmbeddingModel} prontos, sem precisar saber como foram
 * construídos).</p>
 */
@Configuration
public class LangChain4jConfig {

    /**
     * Modelo de chat (LLM) que gera texto em linguagem natural.
     *
     * <p>Aqui usamos o Ollama rodando localmente (ver docker-compose.yml)
     * com o modelo {@code llama3.1}. É esse bean que efetivamente
     * "conversa" — recebe uma lista de mensagens (sistema + usuário) e
     * devolve uma resposta gerada pelo modelo.</p>
     */
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

    /**
     * Modelo de embeddings: transforma texto em um vetor numérico (uma
     * lista de números, ex: 768 posições) que representa o "significado"
     * semântico daquele texto.
     *
     * <p>É usado em dois momentos: (1) na ingestão, para transformar cada
     * chunk de documento em vetor antes de salvar no banco; (2) na
     * consulta, para transformar a pergunta do usuário em vetor e
     * comparar com os vetores salvos (busca por similaridade de
     * cosseno — textos com significado parecido geram vetores
     * "próximos" no espaço vetorial).</p>
     */
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

    /**
     * Banco vetorial: onde os embeddings (vetores) e o texto original de
     * cada chunk ficam armazenados, prontos para busca por similaridade.
     *
     * <p>Usamos uma implementação própria ({@link JdbcPgVectorEmbeddingStore})
     * ao invés de uma pronta do LangChain4j, para ter controle total do
     * SQL (extensão {@code pgvector} do Postgres) e poder guardar
     * metadados extras (nome do arquivo, número da página, hash do
     * chunk para evitar duplicatas).</p>
     */
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
