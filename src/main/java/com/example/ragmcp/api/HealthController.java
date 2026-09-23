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

    private String checkPostgres() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String checkOllama() {
        try {
            ollamaClient.get().uri("/api/tags").retrieve().toBodilessEntity();
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    public record HealthResponse(String status, String postgres, String ollama) {
    }
}
