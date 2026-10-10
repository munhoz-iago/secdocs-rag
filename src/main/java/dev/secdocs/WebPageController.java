package dev.secdocs;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import dev.secdocs.answer.AnswerService;

@Controller
public class WebPageController {
    // Only pinned OWASP Markdown files: no query, fragment, port, userinfo, percent-encoding or backslash.
    private static final Pattern SAFE_SOURCE_URL = Pattern.compile(
            "https://github\\.com/OWASP/(?:CheatSheetSeries|Top10|ASVS)/blob/[0-9a-f]{40}(?:/[A-Za-z0-9_.-]+)+\\.md");

    private final AnswerService answers;

    public WebPageController(AnswerService answers) {
        this.answers = answers;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("question", "");
        model.addAttribute("result", null);
        return "index";
    }

    @PostMapping("/consultar")
    public String ask(@RequestParam String question, Model model) throws Exception {
        String clean = question.trim();
        model.addAttribute("question", clean);
        if (clean.isEmpty() || clean.length() > 500) {
            model.addAttribute("error", "Escreva uma pergunta de até 500 caracteres.");
            return "index";
        }
        AnswerService.AnswerResponse result = answers.answer(clean);
        model.addAttribute("result", result);
        model.addAttribute("citations", safeCitations(result.citations()));
        return "index";
    }

    private static List<DisplayCitation> safeCitations(List<AnswerService.Citation> citations) {
        return citations.stream().map(citation -> new DisplayCitation(citation.sourceTitle(),
                citation.section(), citation.excerpt(), safeUrl(citation.sourceUrl()))).toList();
    }

    static String safeUrl(String sourceUrl) {
        if (sourceUrl == null || !SAFE_SOURCE_URL.matcher(sourceUrl).matches()
                || sourceUrl.contains("/./") || sourceUrl.contains("/../")) {
            return null;
        }
        try {
            URI uri = URI.create(sourceUrl);
            if (!sourceUrl.equals(uri.normalize().toString()) || !"github.com".equals(uri.getHost())) {
                return null;
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed source URL is displayed only as text.
            return null;
        }
        return sourceUrl;
    }

    public record DisplayCitation(String title, String section, String excerpt, String url) {
    }
}
