package dev.secdocs.answer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import dev.secdocs.search.SearchEngine;
import dev.secdocs.search.SearchHit;
import dev.secdocs.search.SearchMode;

@Service
public class AnswerService {

    private static final double MIN_VECTOR_SIMILARITY = 0.55;
    private static final Pattern FENCED_CODE = Pattern.compile("```([^`\\n]*)\\n([\\s\\S]*?)```");
    private static final Pattern FENCE_LANGUAGE = Pattern.compile("[A-Za-z0-9_+-]{0,20}");
    private static final Pattern INLINE_CODE = Pattern.compile("(?<!`)`([^`\\n]+)`(?!`)");
    private static final Pattern UNMARKED_SQL = Pattern.compile(
            "(?i)\\b(?:drop\\W+table|truncate\\W+table|union\\W+(?:all\\W+)?select|select\\W*\\*"
                    + "|delete\\W+from|insert\\W+into)",
            Pattern.UNICODE_CHARACTER_CLASS);
    // Code signals outside verified code spans. Typographic quotes and plain quoted terms stay allowed;
    // ASCII quotes are only refused next to SQL operators, comments or tautologies. A semicolon is
    // ordinary punctuation in Portuguese prose, so it is refused only in a code-like position.
    private static final String CODE_SEMICOLON =
            "[)\\]}'\"]\\s*;"
                    + "|;(?=\\S)"
                    + "|;\\s*(?:--|#|/\\*|//)"
                    + "|(?i:;\\s*(?:select|insert|update|delete|drop|union|exec|execute|shutdown|truncate"
                    + "|alter|create|grant|declare|waitfor)\\b)"
                    + "|=\\s*[^\\s;]+\\s*;"
                    + "|(?i:\\b(?:select\\b[^;\\n]*\\bfrom|update\\s+\\w+\\s+set)\\b[^;\\n]*;)";
    private static final Pattern UNMARKED_CODE = Pattern.compile(
            "`|~~~|" + CODE_SEMICOLON + "|<[a-zA-Z/!]|\\||\\$\\(|&&"
                    + "|(?m:^(?: {4}|\\t)[ \\t]*(?![-*+]\\s|\\d+[.)]\\s)\\S)"
                    + "|(?<![\\w-])--?[a-zA-Z]"
                    + "|['\"]\\s*(?:[=<>]|--|#|/\\*)|[=<>]\\s*['\"]"
                    + "|(?i:\\b(?:or|and)\\W+\\w+\\W*(?:=|<|>|\\blike\\b))",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final String NO_ANSWER = "Não encontrei informação suficiente nos documentos consultados para responder com segurança.";

    private final SearchEngine searchEngine;
    private final AnswerModel answerModel;

    public AnswerService(SearchEngine searchEngine, AnswerModel answerModel) {
        this.searchEngine = searchEngine;
        this.answerModel = answerModel;
    }

    public AnswerResponse answer(String question) throws Exception {
        Map<SearchMode, List<SearchHit>> results = searchEngine.compare(question, 5);
        List<SearchHit> vector = results.get(SearchMode.VECTOR);
        if (vector.isEmpty() || vector.getFirst().score() < MIN_VECTOR_SIMILARITY) {
            return abstain();
        }

        // Keep retrieval selection explicit until an independent evaluation justifies changing it.
        List<SearchHit> evidence = vector;
        AnswerModel.ModelAnswer draft = answerModel.generate(question, evidence);
        AnswerResponse response = validate(draft, evidence);
        if (response.abstained() && hasLiteralValidationFailure(draft)) {
            return validate(answerModel.generateRepair(question, evidence), evidence);
        }
        return response;
    }

    private static boolean hasLiteralValidationFailure(AnswerModel.ModelAnswer draft) {
        // Other abstentions are deliberate or structurally invalid, so only citation/code failures qualify.
        return draft != null && !draft.abstained() && draft.answer() != null
                && !draft.answer().isBlank() && draft.answer().length() <= 4000
                && draft.supports() != null && !draft.supports().isEmpty()
                && draft.supports().size() <= 3;
    }

    public static AnswerResponse validate(AnswerModel.ModelAnswer draft, List<SearchHit> evidence) {
        if (draft == null || draft.abstained() || draft.answer() == null
                || draft.answer().isBlank() || draft.answer().length() > 4000
                || draft.supports() == null || draft.supports().isEmpty()
                || draft.supports().size() > 3) {
            return abstain();
        }
        Set<String> allowedIds = new HashSet<>();
        for (SearchHit hit : evidence) {
            allowedIds.add(hit.id());
        }
        List<AnswerModel.Support> verifiedSupports = draft.supports().stream()
                .filter(support -> allowedIds.contains(support.id())
                        && support.quote() != null && support.quote().length() >= 20
                        && support.quote().length() <= 600
                        && evidence.stream().anyMatch(hit -> hit.id().equals(support.id())
                                && hit.content().contains(support.quote())))
                .toList();
        if (verifiedSupports.size() != draft.supports().size()
                || !codeIsLiteral(draft.answer(), verifiedSupports)) {
            return abstain();
        }
        List<Citation> citations = verifiedSupports.stream()
                .map(support -> {
                    SearchHit hit = evidence.stream().filter(item -> item.id().equals(support.id()))
                            .findFirst().orElseThrow();
                    return new Citation(hit.id(), hit.sourceTitle(), hit.section(), hit.sourceUrl(), support.quote());
                }).distinct()
                .toList();
        return new AnswerResponse(draft.answer().trim(), false, citations);
    }

    private static boolean codeIsLiteral(String answer, List<AnswerModel.Support> supports) {
        var fenced = FENCED_CODE.matcher(answer);
        while (fenced.find()) {
            if (!FENCE_LANGUAGE.matcher(fenced.group(1).strip()).matches()
                    || !appearsInQuote(fenced.group(2).trim(), supports)) {
                return false;
            }
        }
        String withoutCode = fenced.replaceAll(" ");
        var inline = INLINE_CODE.matcher(withoutCode);
        while (inline.find()) {
            if (!appearsInQuote(inline.group(1), supports)) {
                return false;
            }
        }
        String prose = inline.replaceAll(" ");
        return !UNMARKED_CODE.matcher(prose).find() && !UNMARKED_SQL.matcher(prose).find();
    }

    private static boolean appearsInQuote(String code, List<AnswerModel.Support> supports) {
        return !code.isBlank() && supports.stream().anyMatch(support -> support.quote().contains(code));
    }

    private static AnswerResponse abstain() {
        return new AnswerResponse(NO_ANSWER, true, List.of());
    }

    public record AnswerResponse(String answer, boolean abstained, List<Citation> citations) {
    }

    public record Citation(String id, String sourceTitle, String section, String sourceUrl, String excerpt) {
    }
}
