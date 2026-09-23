package com.example.ragmcp.ingestion;

public record IngestionResult(
        String fileName,
        int chunksCreated,
        int chunksSkippedAsDuplicate,
        boolean success,
        String errorMessage
) {
}
