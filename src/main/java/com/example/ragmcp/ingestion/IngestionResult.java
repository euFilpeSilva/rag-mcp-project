package com.example.ragmcp.ingestion;

/**
 * Relatório de uma ingestão (record = classe imutável só com dados,
 * gerada automaticamente pelo Java a partir dos campos abaixo).
 *
 * @param fileName                nome do arquivo processado
 * @param chunksCreated           quantos chunks novos foram salvos no banco
 * @param chunksSkippedAsDuplicate quantos chunks já existiam (mesmo hash) e foram ignorados
 * @param success                 {@code false} se ocorreu erro durante a ingestão
 * @param errorMessage            mensagem de erro, quando {@code success=false}
 */
public record IngestionResult(
        String fileName,
        int chunksCreated,
        int chunksSkippedAsDuplicate,
        boolean success,
        String errorMessage
) {
}
