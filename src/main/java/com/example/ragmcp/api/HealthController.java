package com.example.ragmcp.api;

import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Endpoint {@code GET /api/health}: verifica rapidamente se as duas
 * dependências externas críticas (Postgres e Ollama) estão respondendo,
 * útil para diagnóstico manual e para checks de infraestrutura (ex:
 * Docker healthcheck, monitoramento).
 */
@RestController
@RequestMapping(path = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final RestClient ollamaClient;

    public HealthController(DataSource dataSource, @Value("${rag.ollama.base-url}") String ollamaBaseUrl) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        this.ollamaClient = RestClient.builder()
                .baseUrl(ollamaBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @GetMapping
    public HealthResponse health() {
        String postgres = checkPostgres();
        String ollama = checkOllama();
        String status = "UP".equals(postgres) && "UP".equals(ollama) ? "UP" : "DEGRADED";
        return new HealthResponse(status, postgres, ollama);
    }

    /** Testa a conexão com o Postgres rodando um {@code SELECT 1} simples. */
    private String checkPostgres() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    /** Testa se o servidor Ollama está no ar chamando o endpoint de listagem de modelos. */
    private String checkOllama() {
        try {
            ollamaClient.get().uri("/api/tags").retrieve().toBodilessEntity();
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    /** {@code status} é "UP" só quando ambas as dependências estão UP; caso contrário "DEGRADED". */
    public record HealthResponse(String status, String postgres, String ollama) {
    }
}
