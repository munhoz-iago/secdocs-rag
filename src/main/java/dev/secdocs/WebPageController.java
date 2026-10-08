package dev.secdocs;

import java.net.URI;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import dev.secdocs.answer.AnswerService;

@Controller
public class WebPageController {
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

    private static String safeUrl(String sourceUrl) {
        try {
            URI uri = URI.create(sourceUrl);
            String host = uri.getHost();
            String path = uri.getPath();
            if ("https".equals(uri.getScheme()) && (
                    "cheatsheetseries.owasp.org".equals(host)
                    || ("github.com".equals(host) && path.startsWith("/OWASP/CheatSheetSeries/blob/")))) {
                return uri.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed source URL is displayed only as text.
        }
        return null;
    }

    public record DisplayCitation(String title, String section, String excerpt, String url) {
    }
}
