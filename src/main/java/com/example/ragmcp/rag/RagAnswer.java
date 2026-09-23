package com.example.ragmcp.rag;

import java.util.List;

/**
 * Resposta final devolvida ao usuário/cliente.
 *
 * @param answer              texto gerado pelo LLM (ou mensagem de "não sei")
 * @param sources             chunks/documentos usados como base da resposta
 * @param insufficientContext {@code true} quando o sistema não encontrou
 *                            contexto confiável (nem chamou o LLM, ou a
 *                            resposta gerada foi rejeitada pela verificação
 *                            anti-alucinação) — sinaliza que um fallback
 *                            MCP externo (spec 04) poderia ser tentado.
 */
public record RagAnswer(
        String answer,
        List<SourceReference> sources,
        boolean insufficientContext
) {
}
