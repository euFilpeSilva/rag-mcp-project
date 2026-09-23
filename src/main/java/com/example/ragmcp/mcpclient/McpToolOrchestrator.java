package com.example.ragmcp.mcpclient;

public interface McpToolOrchestrator {

    /**
     * Tenta responder usando ferramentas MCP externas quando o RAG local
     * não teve contexto suficiente.
     */
    FallbackResult tryFallback(String question);
}
