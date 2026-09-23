package com.example.ragmcp.rag;

import java.util.List;

public record RagAnswer(
        String answer,
        List<SourceReference> sources,
        boolean insufficientContext
) {
}
