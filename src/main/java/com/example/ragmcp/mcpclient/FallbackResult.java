package com.example.ragmcp.mcpclient;

public record FallbackResult(
        boolean used,
        String toolName,
        String answer,
        String rawToolOutput
) {
}
