ALTER TABLE document_chunks
    ALTER COLUMN embedding TYPE vector(1024) USING embedding::vector(1024),
    ADD COLUMN embedding_model TEXT,
    ADD COLUMN embedded_at TIMESTAMPTZ;

CREATE INDEX document_chunks_embedding_idx
    ON document_chunks USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;
