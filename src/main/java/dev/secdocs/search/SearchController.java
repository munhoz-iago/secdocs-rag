package dev.secdocs.search;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchEngine engine;

    public SearchController(SearchEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/api/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) throws Exception {
        SearchMode mode = request.mode() == null ? SearchMode.HYBRID : request.mode();
        int limit = request.limit() == null ? 5 : request.limit();
        List<HitView> hits = engine.search(request.question().trim(), mode, limit).stream()
                .map(hit -> new HitView(hit.id(), hit.sourceTitle(), hit.section(), hit.sourceUrl(),
                        hit.snippet(), hit.score()))
                .toList();
        return new SearchResponse(request.question().trim(), mode, hits);
    }

    public record SearchRequest(@NotBlank @Size(max = 500) String question,
                                SearchMode mode, @Min(1) @Max(10) Integer limit) {
    }

    public record SearchResponse(String question, SearchMode mode, List<HitView> hits) {
    }

    public record HitView(String id, String sourceTitle, String section, String sourceUrl,
                          String snippet, double score) {
    }
}
