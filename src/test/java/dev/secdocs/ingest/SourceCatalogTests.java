package dev.secdocs.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceCatalogTests {
    @Test
    void pinsAllThreeOwaspCollections() throws Exception {
        var sources = new SourceCatalog().load();

        assertThat(sources).hasSize(30);
        assertThat(sources).extracting(OwaspSource::repository)
                .containsOnly("CheatSheetSeries", "Top10", "ASVS");
        assertThat(sources).extracting(OwaspSource::id).doesNotHaveDuplicates();
        assertThat(sources).allSatisfy(source -> {
            assertThat(source.revision()).matches("[0-9a-f]{40}");
            assertThat(source.license()).isEqualTo("CC-BY-SA-4.0");
            assertThat(source.downloadUrl()).contains(source.revision());
        });
    }
}
