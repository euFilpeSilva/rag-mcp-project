package com.example.ragmcp.ingestion;

import java.nio.file.Path;
import java.util.List;

/**
 * Contrato do serviço de ingestão de documentos (spec 01). É a "porta de
 * entrada" de dados para o RAG: sem ingestão, não há o que buscar depois.
 */
public interface DocumentIngestionService {

    /**
     * Ingere um único arquivo (PDF ou TXT).
     * @return relatório com número de chunks criados/ignorados (duplicados)
     */
    IngestionResult ingestFile(Path filePath);

    /**
     * Ingere todos os arquivos suportados de um diretório (não recursivo).
     */
    List<IngestionResult> ingestDirectory(Path directoryPath);
}
