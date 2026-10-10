package dev.secdocs.ingest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class StoreChunksTests {

    private static final OwaspSource FIRST = new OwaspSource(
            "first", "First", "CheatSheetSeries", "first.md", "revision-a", "CC-BY-SA-4.0");
    private static final OwaspSource SECOND = new OwaspSource(
            "second", "Second", "Top10", "second.md", "revision-b", "CC-BY-SA-4.0");
    private static final List<OwaspSource> CATALOG = List.of(FIRST, SECOND);

    @Test
    void acceptsCompleteCurrentInput() {
        assertThatCode(() -> StoreChunks.validatePreflight(
                List.of(chunk("first-1", FIRST), chunk("second-1", SECOND)), CATALOG))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingCatalogSource() {
        assertThatThrownBy(() -> StoreChunks.validatePreflight(List.of(chunk("first-1", FIRST)), CATALOG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("second");
    }

    @Test
    void rejectsDuplicateChunkIdAcrossSources() {
        assertThatThrownBy(() -> StoreChunks.validatePreflight(
                List.of(chunk("same-id", FIRST), chunk("same-id", SECOND)), CATALOG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ID de trecho duplicado");
    }

    @Test
    void rejectsStaleRevisionAndSourceUrl() {
        DocumentChunk current = chunk("first-1", FIRST);
        DocumentChunk staleRevision = new DocumentChunk(current.id(), current.sourceId(), current.sourceTitle(),
                current.section(), current.sourceUrl(), "old-revision", current.license(), current.text());
        DocumentChunk staleUrl = new DocumentChunk(current.id(), current.sourceId(), current.sourceTitle(),
                current.section(), "https://example.org/old", current.revision(), current.license(), current.text());

        for (DocumentChunk stale : List.of(staleRevision, staleUrl)) {
            assertThatThrownBy(() -> StoreChunks.validatePreflight(
                    List.of(stale, chunk("second-1", SECOND)), CATALOG))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Metadados desatualizados");
        }
    }

    @Test
    void rejectsSourceOutsideCatalog() {
        OwaspSource removed = new OwaspSource(
                "removed", "Removed", "Top10", "removed.md", "revision-c", "CC-BY-SA-4.0");
        assertThatThrownBy(() -> StoreChunks.validatePreflight(
                List.of(chunk("first-1", FIRST), chunk("second-1", SECOND), chunk("removed-1", removed)), CATALOG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fonte fora do catálogo atual");
    }

    private static DocumentChunk chunk(String id, OwaspSource source) {
        return new DocumentChunk(id, source.id(), source.title(), "Section", source.sourceUrl(),
                source.revision(), source.license(), "Content");
    }
}
