package com.example.ragmcp.testsupport;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInput;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DeterministicEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSION = 768;
    private static final float KEYWORD_WEIGHT = 5.0f;
    private static final Map<String, Integer> KEYWORD_DIMENSIONS = Map.ofEntries(
            Map.entry("garantia", 10),
            Map.entry("12", 11),
            Map.entry("meses", 12),
            Map.entry("capitulo", 20),
            Map.entry("2", 21),
            Map.entry("instalacao", 22),
            Map.entry("220v", 23),
            Map.entry("mcp", 30),
            Map.entry("servidor", 31),
            Map.entry("ingestao", 32),
            Map.entry("perguntas", 33));
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "o", "e", "de", "do", "da", "das", "dos", "um", "uma", "para", "com", "sem",
            "qual", "quais", "como", "sobre", "produto", "local", "locais", "documentos");

    @Override
    public EmbeddingResponse doEmbed(EmbeddingRequest request) {
        List<Embedding> embeddings = request.inputs().stream()
                .map(EmbeddingInput::text)
                .map(this::embeddingFor)
                .toList();
        return EmbeddingResponse.builder()
                .embeddings(embeddings)
                .modelName(modelName())
                .build();
    }

    @Override
    public Set<ContentType> supportedContentTypes() {
        return Set.of(ContentType.TEXT);
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public String modelName() {
        return "deterministic-test-embedding";
    }

    public Embedding embeddingFor(String text) {
        float[] vector = new float[DIMENSION];
        String normalized = normalize(text);
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            Integer specialDimension = KEYWORD_DIMENSIONS.get(token);
            if (specialDimension != null) {
                vector[specialDimension] += KEYWORD_WEIGHT;
                continue;
            }
            if (STOP_WORDS.contains(token)) {
                continue;
            }
            int index = Math.floorMod(token.hashCode(), DIMENSION);
            vector[index] += 1.0f;
        }

        double norm = 0.0d;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0.0d) {
            vector[0] = 1.0f;
        } else {
            float scale = (float) (1.0d / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return Embedding.from(vector);
    }

    public String toSqlVectorLiteral(String text) {
        return toSqlVectorLiteral(embeddingFor(text).vector());
    }

    public String toSqlVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        builder.append(']');
        return builder.toString();
    }

    private String normalize(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", " ")
                        .trim()
                        .split("\\s+"))
                .filter(token -> !token.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
