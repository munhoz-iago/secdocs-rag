package dev.secdocs.eval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import dev.secdocs.answer.AnswerService;
import dev.secdocs.ingest.SourceCatalog;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Small, opt-in development evaluation; acceptance and citation matching are not semantic scores. */
@Component
@ConditionalOnProperty(prefix = "secdocs", name = "eval-generation", havingValue = "true")
public class EvaluateGeneration implements ApplicationRunner {

    static final String ACCEPTED_ANSWER_DEFINITION =
            "!abstained && nonblank answer && nonempty citations; does not measure semantic correctness";
    static final String CITATION_MATCH_DEFINITION =
            "At least one cited chunk ID belongs to expectedChunkIds; independent of acceptance and semantic correctness";

    private final AnswerService answers;
    private final Environment environment;
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public EvaluateGeneration(AnswerService answers, Environment environment) {
        this.answers = answers;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        evaluate(Path.of("evals", "generation-set.json"), Path.of("evals", "golden-set.json"),
                Path.of("evals", "generation-results.json"));
    }

    // Paths are explicit here so validation and reporting can be tested without application startup.
    void evaluate(Path fixturePath, Path goldenPath, Path outputPath) throws Exception {
        byte[] fixtureBytes = Files.readAllBytes(fixturePath);
        GenerationSet fixture = parseFixture(new String(fixtureBytes, StandardCharsets.UTF_8));
        validateAgainstGolden(fixture, Files.readString(goldenPath, StandardCharsets.UTF_8));
        String citationStrategyFallback = requireText(environment.getProperty(
                "secdocs.eval-generation-citation-strategy", "unspecified"), "citationStrategy");
        String chatModel = System.getenv().getOrDefault("SECDOCS_CHAT_MODEL", "qwen3:1.7b");
        String embeddingModel = System.getenv().getOrDefault("SECDOCS_EMBED_MODEL", "bge-m3:567m");
        String ollamaUrl = System.getenv().getOrDefault("SECDOCS_OLLAMA_URL", "http://localhost:11434");
        String fingerprint = null;
        String fingerprintError = null;
        try {
            fingerprint = chatModelFingerprint(ollamaUrl, chatModel);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (IOException | RuntimeException unavailable) {
            fingerprintError = unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage();
        }

        List<CaseResult> results = new ArrayList<>();
        for (GenerationQuestion question : fixture.questions()) {
            long started = System.nanoTime();
            var response = answers.answer(question.question());
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            // Serializing the actual response preserves optional diagnostics without depending on new accessors.
            Map<String, Object> rawResponse = responseFields(response);
            results.add(new CaseResult(question, rawResponse, elapsedMillis));
            System.out.printf("Geração development %d/%d: %s%n", results.size(), fixture.questions().size(), question.id());
        }

        Map<String, Object> report = report(fixture, results, fixtureSha256(fixtureBytes),
                chatModel, embeddingModel, citationStrategy(results, citationStrategyFallback), Instant.now());
        report.put("chatModelFingerprint", fingerprint);
        if (fingerprintError != null) {
            report.put("chatModelFingerprintError", fingerprintError);
        }
        report.put("corpusRevisions", new SourceCatalog().load().stream().collect(Collectors.toMap(
                source -> source.repository(), source -> source.revision(),
                (first, second) -> first.equals(second) ? first : first + "," + second, TreeMap::new)));
        writeReport(outputPath, report);
        System.out.println("Avaliação de geração development gravada em " + outputPath);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseFields(AnswerService.AnswerResponse response) {
        if (response == null) {
            throw new IllegalStateException("AnswerService devolveu uma resposta nula");
        }
        return objectMapper.convertValue(response, Map.class);
    }

    GenerationSet parseFixture(String json) {
        if (!(readJson(json) instanceof Map<?, ?> root)
                || !"development".equals(root.get("data")) || !Boolean.FALSE.equals(root.get("independent"))) {
            throw new IllegalArgumentException("A fixture deve declarar data=development e independent=false");
        }
        String strategy = requireText(root.get("strategy"), "strategy");
        if (!(root.get("questions") instanceof List<?> rows) || rows.size() != 8) {
            throw new IllegalArgumentException("A fixture de geração deve conter exatamente 8 perguntas");
        }
        List<GenerationQuestion> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> texts = new HashSet<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row) || !(row.get("expectedAnswerable") instanceof Boolean answerable)) {
                throw new IllegalArgumentException("Pergunta inválida ou expectedAnswerable não booleano");
            }
            var question = new GenerationQuestion(requireText(row.get("id"), "id"),
                    requireText(row.get("language"), "language"), requireText(row.get("question"), "question"),
                    answerable, chunkIds(row.get("expectedChunkIds")));
            if (!ids.add(question.id())
                    || !texts.add(question.question().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("ID ou pergunta duplicada na fixture: " + question.id());
            }
            questions.add(question);
        }
        if (questions.stream().filter(GenerationQuestion::expectedAnswerable).count() != 6) {
            throw new IllegalArgumentException("A fixture deve conter 6 perguntas respondíveis e 2 fora do corpus");
        }
        return new GenerationSet("development", strategy, false, List.copyOf(questions));
    }

    void validateAgainstGolden(GenerationSet fixture, String goldenJson) {
        if (!(readJson(goldenJson) instanceof List<?> rows)) {
            throw new IllegalArgumentException("Golden set deve ser uma lista");
        }
        Map<String, Map<?, ?>> golden = new HashMap<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("Pergunta inválida no golden set");
            }
            String id = requireText(row.get("id"), "golden.id");
            if (golden.putIfAbsent(id, row) != null) {
                throw new IllegalArgumentException("ID duplicado no golden set: " + id);
            }
        }
        for (GenerationQuestion question : fixture.questions()) {
            Map<?, ?> reference = golden.get(question.id());
            if (reference == null || !question.question().equals(reference.get("question"))
                    || !Boolean.valueOf(question.expectedAnswerable()).equals(reference.get("answerable"))
                    || !question.expectedChunkIds().equals(chunkIds(reference.get("expectedChunkIds")))) {
                throw new IllegalArgumentException("Pergunta ou IDs divergentes do golden set: " + question.id());
            }
        }
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JacksonException invalidJson) {
            throw new IllegalArgumentException("JSON de avaliação inválido", invalidJson);
        }
    }

    private static String requireText(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())) {
            throw new IllegalArgumentException("Texto ausente, vazio ou inválido: " + field);
        }
        return text;
    }

    private static List<String> chunkIds(Object value) {
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException("expectedChunkIds deve ser uma lista");
        }
        List<String> ids = raw.stream().map(id -> requireText(id, "expectedChunkIds")).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("ID de trecho duplicado em expectedChunkIds");
        }
        return ids;
    }

    static String fixtureSha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String citationStrategy(List<CaseResult> results, String fallback) {
        Set<String> strategies = results.stream().map(result -> result.response().get("citationStrategy"))
                .filter(value -> value != null).map(value -> requireText(value, "response.citationStrategy"))
                .collect(Collectors.toSet());
        if (strategies.size() > 1) {
            throw new IllegalArgumentException("Estratégias de citação diferentes na mesma execução: " + strategies);
        }
        return strategies.isEmpty() ? requireText(fallback, "citationStrategy") : strategies.iterator().next();
    }

    private String chatModelFingerprint(String baseUrl, String model) throws IOException, InterruptedException {
        URI endpoint = URI.create((baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "api/tags");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Metadados do modelo de chat: HTTP " + response.statusCode());
        }
        Object metadata = readJson(response.body());
        if (metadata instanceof Map<?, ?> tags && tags.get("models") instanceof List<?> models) {
            for (Object item : models) {
                if (item instanceof Map<?, ?> entry
                        && (model.equals(entry.get("name")) || model.equals(entry.get("model")))
                        && entry.get("digest") instanceof String digest && !digest.isBlank()) {
                    return model + "@" + digest;
                }
            }
        }
        throw new IOException("Digest não encontrado para o modelo de chat: " + model);
    }

    static Map<String, Object> report(GenerationSet fixture, List<CaseResult> results, String fixtureHash,
                                    String chatModel, String embeddingModel, String citationStrategy, Instant generatedAt) {
        if (!fixture.questions().equals(results.stream().map(CaseResult::question).toList())) {
            throw new IllegalArgumentException("Resultados incompletos ou fora da ordem da fixture");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", generatedAt.toString());
        report.put("data", fixture.data());
        report.put("strategy", fixture.strategy());
        report.put("independent", fixture.independent());
        report.put("fixturePath", "evals/generation-set.json");
        report.put("fixtureSha256", fixtureHash);
        report.put("chatModel", chatModel);
        report.put("embeddingModel", embeddingModel);
        report.put("citationStrategy", citationStrategy);
        report.put("acceptedAnswerDefinition", ACCEPTED_ANSWER_DEFINITION);
        report.put("expectedCitationMatchDefinition", CITATION_MATCH_DEFINITION);
        report.put("elapsedMillisDefinition", "Wall time around AnswerService.answer, including retrieval, generation and internal retries; excludes metadata lookup");
        report.put("counts", counts(results));
        report.put("medianElapsedMillis", medianElapsedMillis(results));
        report.put("cases", results.stream().map(CaseResult::reportRow).toList());
        return report;
    }

    static Map<String, Object> counts(List<CaseResult> results) {
        long answerable = results.stream().filter(result -> result.question().expectedAnswerable()).count();
        long answered = results.stream().filter(result -> result.question().expectedAnswerable() && !result.abstained()).count();
        long accepted = results.stream().filter(result -> result.question().expectedAnswerable() && result.acceptedAnswer()).count();
        long matched = results.stream().filter(result -> result.question().expectedAnswerable() && result.expectedCitationMatch()).count();
        long outside = results.size() - answerable;
        long outsideAbstained = results.stream().filter(result -> !result.question().expectedAnswerable() && result.abstained()).count();
        return Map.of("answerable", Map.of("total", answerable, "answered", answered,
                        "falseRefusals", answerable - answered, "acceptedAnswers", accepted, "expectedCitationMatches", matched),
                "outside", Map.of("total", outside, "abstained", outsideAbstained, "answered", outside - outsideAbstained));
    }

    static double medianElapsedMillis(List<CaseResult> results) {
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Não há resultados para calcular a mediana");
        }
        List<Long> sorted = results.stream().map(CaseResult::elapsedMillis).sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : sorted.get(middle - 1) / 2.0 + sorted.get(middle) / 2.0;
    }

    void writeReport(Path path, Map<String, Object> report) throws IOException {
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
    }

    record GenerationSet(String data, String strategy, boolean independent, List<GenerationQuestion> questions) {
    }

    record GenerationQuestion(String id, String language, String question,
                              boolean expectedAnswerable, List<String> expectedChunkIds) {
        GenerationQuestion {
            requireText(id, "id");
            requireText(question, "question");
            if (!Set.of("pt", "en").contains(language) || expectedAnswerable == expectedChunkIds.isEmpty()) {
                throw new IllegalArgumentException("Idioma ou IDs incompatíveis com a pergunta: " + id);
            }
            expectedChunkIds = List.copyOf(expectedChunkIds);
        }
    }

    record CaseResult(GenerationQuestion question, Map<String, Object> response, long elapsedMillis) {
        CaseResult {
            if (elapsedMillis < 0 || response == null || !(response.get("abstained") instanceof Boolean)
                    || !(response.get("citations") instanceof List<?> citations)
                    || (response.get("answer") != null && !(response.get("answer") instanceof String))) {
                throw new IllegalArgumentException("Resposta ou latência inválida: " + question.id());
            }
            for (Object item : citations) {
                if (!(item instanceof Map<?, ?> citation)) {
                    throw new IllegalArgumentException("Citação inválida: " + question.id());
                }
                requireText(citation.get("id"), "citation.id");
            }
            response = Collections.unmodifiableMap(new LinkedHashMap<>(response));
        }

        boolean abstained() {
            return Boolean.TRUE.equals(response.get("abstained"));
        }

        boolean acceptedAnswer() {
            return !abstained() && response.get("answer") instanceof String text && !text.isBlank()
                    && !((List<?>) response.get("citations")).isEmpty();
        }

        List<String> matchedExpectedChunkIds() {
            Set<Object> cited = ((List<?>) response.get("citations")).stream()
                    .map(item -> ((Map<?, ?>) item).get("id")).collect(Collectors.toSet());
            return question.expectedChunkIds().stream().filter(cited::contains).toList();
        }

        boolean expectedCitationMatch() {
            return !matchedExpectedChunkIds().isEmpty();
        }

        Map<String, Object> reportRow() {
            Map<String, Object> row = new LinkedHashMap<>(response);
            row.put("id", question.id());
            row.put("language", question.language());
            row.put("question", question.question());
            row.put("expectedAnswerable", question.expectedAnswerable());
            row.put("elapsedMillis", elapsedMillis);
            row.put("acceptedAnswer", acceptedAnswer());
            row.put("expectedChunkIds", question.expectedChunkIds());
            row.put("matchedExpectedChunkIds", matchedExpectedChunkIds());
            row.put("expectedCitationMatch", question.expectedAnswerable() ? expectedCitationMatch() : null);
            return row;
        }
    }
}
