package com.example.ragmcp.mcpclient;

/**
 * Resultado de uma tentativa de fallback via MCP externo.
 *
 * @param used           {@code true} se alguma ferramenta externa foi de
 *                       fato chamada para tentar responder
 * @param toolName       nome da ferramenta MCP externa chamada (ou null)
 * @param answer         resposta final já formatada com o prefixo
 *                       "[Fonte externa: ...]" (ou null se {@code used=false})
 * @param rawToolOutput  saída bruta da ferramenta externa, útil para
 *                       depuração/logs
 */
public record FallbackResult(
        boolean used,
        String toolName,
        String answer,
        String rawToolOutput
) {
}
