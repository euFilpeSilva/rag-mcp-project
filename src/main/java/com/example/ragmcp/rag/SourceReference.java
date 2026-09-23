package com.example.ragmcp.rag;

public record SourceReference(
        String fileName,
        Integer pageNumber,
        double similarityScore
) {
}
