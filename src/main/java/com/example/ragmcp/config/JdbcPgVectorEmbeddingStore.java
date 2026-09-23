package com.example.ragmcp.config;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcPgVectorEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final Logger log = LoggerFactory.getLogger(JdbcPgVectorEmbeddingStore.class);
    private static final String SOURCE_FILE = "source_file";
    private static final String PAGE_NUMBER = "page_number";
    private static final String CHUNK_HASH = "chunk_hash";

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final String datasourceUrl;
    private final String username;
    private final String password;
    private final String tableName;
    private final int dimension;

    public JdbcPgVectorEmbeddingStore(
            DataSource dataSource,
            JdbcTemplate jdbcTemplate,
            String datasourceUrl,
            String username,
            String password,
            String tableName,
            int dimension) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.datasourceUrl = datasourceUrl;
        this.username = username;
        this.password = password;
        this.tableName = tableName;
        this.dimension = dimension;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    public String datasourceUrl() {
        return datasourceUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String tableName() {
        return tableName;
    }

    public int dimension() {
        return dimension;
    }

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("Use add(Embedding, TextSegment) to preserve metadata");
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException("Use add(Embedding, TextSegment) to preserve metadata");
    }

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        Metadata metadata = embedded.metadata() == null ? new Metadata() : embedded.metadata();
        jdbcTemplate.update(
                "INSERT INTO " + tableName + " (id, content, embedding, source_file, page_number, chunk_hash) "
                        + "VALUES (?, ?, CAST(? AS vector), ?, ?, ?)",
                UUID.fromString(id),
                embedded.text(),
                vectorToSqlLiteral(embedding),
                metadata.getString(SOURCE_FILE),
                metadata.getInteger(PAGE_NUMBER),
                metadata.getString(CHUNK_HASH));
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException("Use addAll with embedded segments");
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        for (int i = 0; i < embeddings.size(); i++) {
            add(embeddings.get(i), embedded.get(i));
        }
    }

    @Override
    public void removeAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM " + tableName + " WHERE id IN (" + placeholders + ")", ids.toArray());
    }

    @Override
    public void removeAll(Filter filter) {
        throw new UnsupportedOperationException("Filtering delete is not supported");
    }

    @Override
    public void removeAll() {
        jdbcTemplate.update("DELETE FROM " + tableName);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (request.queryEmbedding() == null) {
            throw new IllegalArgumentException("queryEmbedding is required");
        }
        String vector = vectorToSqlLiteral(request.queryEmbedding());
        List<EmbeddingMatch<TextSegment>> matches = jdbcTemplate.query(
                "SELECT id, content, source_file, page_number, chunk_hash, "
                        + "1 - (embedding <=> CAST(? AS vector)) AS similarity "
                        + "FROM " + tableName + " "
                        + "WHERE 1 - (embedding <=> CAST(? AS vector)) >= ? "
                        + "ORDER BY embedding <=> CAST(? AS vector) ASC "
                        + "LIMIT ?",
                (rs, rowNum) -> toMatch(rs),
                vector,
                vector,
                request.minScore(),
                vector,
                request.maxResults());
        return new EmbeddingSearchResult<>(matches);
    }

    private EmbeddingMatch<TextSegment> toMatch(ResultSet rs) throws SQLException {
        Metadata metadata = Metadata.from(Map.of(
                SOURCE_FILE, rs.getString("source_file"),
                CHUNK_HASH, rs.getString("chunk_hash")));
        Integer pageNumber = (Integer) rs.getObject("page_number");
        if (pageNumber != null) {
            metadata.put(PAGE_NUMBER, pageNumber);
        }
        TextSegment segment = TextSegment.from(rs.getString("content"), metadata);
        return new EmbeddingMatch<>(rs.getDouble("similarity"), rs.getString("id"), null, segment);
    }

    private String vectorToSqlLiteral(Embedding embedding) {
        float[] vector = embedding.vector();
        if (vector.length != dimension) {
            log.debug("Embedding dimension {} differs from configured {}", vector.length, dimension);
        }
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
}
