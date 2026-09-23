package com.example.ragmcp.mcpclient;

/**
 * Contrato do fallback via MCP externo (spec 04): usado quando o RAG
 * local ({@code RagQueryService}) não encontrou contexto suficiente
 * para responder com confiança.
 */
public interface McpToolOrchestrator {

    /**
     * Tenta responder usando ferramentas MCP externas quando o RAG local
     * não teve contexto suficiente.
     */
    FallbackResult tryFallback(String question);
}
