package dev.secdocs.embed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

public class OllamaEmbeddingClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI baseUri;
    private final String model;

    public OllamaEmbeddingClient(String baseUrl, String model) {
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.model = model;
    }

    public String modelFingerprint() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("api/tags"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "listar modelos");
        Map<?, ?> tags = objectMapper.readValue(response.body(), Map.class);
        if (tags.get("models") instanceof List<?> models) {
            for (Object item : models) {
                if (item instanceof Map<?, ?> details && model.equals(details.get("name"))) {
                    return model + "@" + details.get("digest");
                }
            }
        }
        throw new IllegalStateException("Modelo não instalado no Ollama: " + model);
    }

    public List<List<Double>> embed(List<String> texts) throws IOException, InterruptedException {
        String payload = objectMapper.writeValueAsString(Map.of("model", model, "input", texts, "truncate", false));
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("api/embed"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "gerar embeddings");
        Map<?, ?> result = objectMapper.readValue(response.body(), Map.class);
        if (!(result.get("embeddings") instanceof List<?> rawVectors) || rawVectors.size() != texts.size()) {
            throw new IOException("Ollama devolveu uma quantidade inesperada de vetores");
        }
        List<List<Double>> vectors = new ArrayList<>();
        for (Object rawVector : rawVectors) {
            if (!(rawVector instanceof List<?> values) || values.size() != 1024) {
                throw new IOException("Ollama devolveu um vetor inválido ou com dimensão diferente de 1024");
            }
            List<Double> vector = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    throw new IOException("Ollama devolveu um vetor com valor inválido");
                }
                vector.add(number.doubleValue());
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static void requireSuccess(HttpResponse<String> response, String operation) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("Falha ao " + operation + ": HTTP " + response.statusCode());
        }
    }
}
