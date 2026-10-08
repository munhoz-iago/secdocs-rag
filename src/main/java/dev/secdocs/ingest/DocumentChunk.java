package dev.secdocs.ingest;

public record DocumentChunk(String id, String sourceId, String sourceTitle, String section, String sourceUrl,
                            String revision, String license, String text) {
}
