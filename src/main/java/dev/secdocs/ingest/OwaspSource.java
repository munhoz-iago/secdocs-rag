package dev.secdocs.ingest;

public record OwaspSource(String id, String title, String path, String revision, String license) {

    public String sourceUrl() {
        return "https://github.com/OWASP/CheatSheetSeries/blob/" + revision + "/" + path;
    }

    public String downloadUrl() {
        return "https://raw.githubusercontent.com/OWASP/CheatSheetSeries/" + revision + "/" + path;
    }
}
