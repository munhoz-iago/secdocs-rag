package dev.secdocs.search;

public record SearchHit(String id, String sourceTitle, String section, String sourceUrl,
                        String content, double score, SearchMode mode) {

    public String snippet() {
        return content.length() <= 320 ? content : content.substring(0, 320) + "…";
    }
}
