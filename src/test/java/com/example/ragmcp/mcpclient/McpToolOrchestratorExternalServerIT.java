package com.example.ragmcp.mcpclient;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("npx não está disponível neste ambiente; CA01 e CA04 da spec 04 dependem de servidor MCP externo real.")
class McpToolOrchestratorExternalServerIT {

    @Test
    void shouldUseFilesystemServerAsFallbackWhenAvailable() {
        // CA01 spec 04
    }

    @Test
    void shouldMarkExternalSourceInFinalFallbackAnswer() {
        // CA04 spec 04
    }
}
