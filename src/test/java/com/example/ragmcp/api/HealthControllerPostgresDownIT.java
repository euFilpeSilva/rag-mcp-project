package com.example.ragmcp.api;

import com.example.ragmcp.RagMcpApplication;
import com.example.ragmcp.testsupport.FakeModelsTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = RagMcpApplication.class,
        properties = {
                "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/ragmcp",
                "spring.datasource.username=ragmcp",
                "spring.datasource.password=ragmcp",
                "spring.datasource.hikari.initializationFailTimeout=0",
                "spring.datasource.hikari.connectionTimeout=1000",
                "spring.sql.init.mode=never",
                "rag.ollama.base-url=http://127.0.0.1:9",
                "mcp.server.stdio-enabled=false"
        })
@Import(FakeModelsTestConfiguration.class)
@AutoConfigureMockMvc
class HealthControllerPostgresDownIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReportPostgresDownWithoutFailingEndpoint() throws Exception {
        // CA03 spec 05
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.postgres").value("DOWN"));
    }
}
