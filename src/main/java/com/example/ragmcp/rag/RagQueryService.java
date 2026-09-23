package com.example.ragmcp.rag;

import java.util.List;

public interface RagQueryService {

    /**
     * Responde a uma pergunta usando RAG sobre os documentos ingeridos.
     */
    RagAnswer ask(String sessionId, String question);

    /**
     * Extensão mínima além da spec para permitir retrieval puro ao servidor MCP.
     */
    List<SourceReference> search(String query, int topK);
}
