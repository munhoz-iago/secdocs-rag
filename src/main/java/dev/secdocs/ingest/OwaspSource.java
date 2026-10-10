package dev.secdocs.ingest;

public record OwaspSource(String id, String title, String repository, String path, String revision, String license) {

    public String sourceUrl() {
        return "https://github.com/OWASP/" + repository + "/blob/" + revision + "/" + path;
    }

    public String downloadUrl() {
        return "https://raw.githubusercontent.com/OWASP/" + repository + "/" + revision + "/" + path;
    }
}
