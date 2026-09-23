CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS document_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    embedding VECTOR(768) NOT NULL,
    source_file TEXT NOT NULL,
    page_number INT,
    chunk_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT now()
);
