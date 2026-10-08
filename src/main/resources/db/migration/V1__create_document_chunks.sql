CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunks (
    id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL,
    source_title TEXT NOT NULL,
    section TEXT NOT NULL,
    source_url TEXT NOT NULL,
    revision TEXT NOT NULL,
    license TEXT NOT NULL,
    content TEXT NOT NULL,
    embedding vector,
    search_text tsvector GENERATED ALWAYS AS (
        to_tsvector('english', source_title || ' ' || section || ' ' || content)
    ) STORED,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX document_chunks_source_id_idx ON document_chunks (source_id);
CREATE INDEX document_chunks_search_text_idx ON document_chunks USING GIN (search_text);
