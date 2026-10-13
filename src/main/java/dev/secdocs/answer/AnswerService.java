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
        long started = System.nanoTime();
        Map<SearchMode, List<SearchHit>> results = searchEngine.compare(question, 5);
        List<SearchHit> vector = results.get(SearchMode.VECTOR);
        if (vector.isEmpty() || vector.getFirst().score() < MIN_VECTOR_SIMILARITY) {
            return withRun(abstain(Outcome.INSUFFICIENT_EVIDENCE), vector, 0, started);
        }

        // Keep retrieval selection explicit until an independent evaluation justifies changing it.
        List<SearchHit> evidence = vector;
        if (EvidencePassages.from(evidence).isEmpty()) {
            return withRun(abstain(Outcome.INSUFFICIENT_EVIDENCE), evidence, 0, started);
        }
        AnswerModel.ModelAnswer draft = answerModel.generate(question, evidence);
        AnswerResponse response = validate(draft, evidence);
        if (response.abstained() && (hasLiteralValidationFailure(draft)
                || draft != null && draft.failure() == AnswerModel.Failure.INVALID_CITATION)) {
            return withRun(validate(answerModel.generateRepair(question, evidence), evidence), evidence, 2, started);
        }
        return withRun(response, evidence, 1, started);
    }

    private static boolean hasLiteralValidationFailure(AnswerModel.ModelAnswer draft) {
        // Other abstentions are deliberate or structurally invalid, so only citation/code failures qualify.
        return draft != null && !draft.abstained() && draft.answer() != null
                && !draft.answer().isBlank() && draft.answer().length() <= 4000
                && draft.supports() != null && !draft.supports().isEmpty()
                && draft.supports().size() <= 3;
    }

    public static AnswerResponse validate(AnswerModel.ModelAnswer draft, List<SearchHit> evidence) {
        if (draft != null && draft.failure() == AnswerModel.Failure.INVALID_RESPONSE) {
            return abstain(Outcome.INVALID_RESPONSE);
        }
        if (draft != null && draft.failure() == AnswerModel.Failure.INVALID_CITATION) {
            return abstain(Outcome.INVALID_CITATION);
        }
        if (draft != null && draft.abstained()) return abstain(Outcome.MODEL_ABSTAINED);
        if (draft == null || draft.answer() == null
                || draft.answer().isBlank() || draft.answer().length() > 4000
                || draft.supports() == null || draft.supports().isEmpty()
                || draft.supports().size() > 3) {
            return abstain(Outcome.INVALID_RESPONSE);
        }
        Set<String> allowedIds = new HashSet<>();
        for (SearchHit hit : evidence) {
            allowedIds.add(hit.id());
        }
        List<AnswerModel.Support> verifiedSupports = draft.supports().stream()
                .filter(support -> support != null && allowedIds.contains(support.id())
                        && support.quote() != null && support.quote().length() >= 20
                        && support.quote().length() <= 600
                        && evidence.stream().anyMatch(hit -> hit.id().equals(support.id())
                                && hit.content().contains(support.quote())))
                .toList();
        if (verifiedSupports.size() != draft.supports().size()) return abstain(Outcome.INVALID_CITATION);
        if (!codeIsLiteral(draft.answer(), verifiedSupports)) return abstain(Outcome.UNSUPPORTED_CODE);
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

    private static AnswerResponse abstain(Outcome outcome) {
        return new AnswerResponse(NO_ANSWER, true, List.of(), outcome, 0, 0, List.of(), "server-passages-v1");
    }

    private static AnswerResponse withRun(AnswerResponse response, List<SearchHit> evidence, int attempts, long started) {
        return new AnswerResponse(response.answer(), response.abstained(), response.citations(), response.outcome(),
                attempts, (System.nanoTime() - started) / 1_000_000, List.copyOf(evidence), "server-passages-v1");
    }

    public enum Outcome {
        ANSWERED, INSUFFICIENT_EVIDENCE, MODEL_ABSTAINED, INVALID_CITATION, UNSUPPORTED_CODE, INVALID_RESPONSE
    }

    public record AnswerResponse(String answer, boolean abstained, List<Citation> citations, Outcome outcome,
                                 int attempts, long durationMs, List<SearchHit> evidence, String citationStrategy) {
        public AnswerResponse(String answer, boolean abstained, List<Citation> citations) {
            this(answer, abstained, citations, abstained ? Outcome.MODEL_ABSTAINED : Outcome.ANSWERED,
                    0, 0, List.of(), "server-passages-v1");
        }
    }

    public record Citation(String id, String sourceTitle, String section, String sourceUrl, String excerpt) {
    }
}
