package dev.secdocs.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "secdocs", name = "ingest", havingValue = "true")
public class OwaspIngestion implements ApplicationRunner {

    private static final int MAX_DOCUMENT_BYTES = 2_000_000;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final SourceCatalog catalog = new SourceCatalog();
    private final MarkdownChunker chunker = new MarkdownChunker();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (OwaspSource source : catalog.load()) {
            String markdown = download(source);
            List<DocumentChunk> documentChunks = chunker.chunk(source, markdown);
            chunks.addAll(documentChunks);
            System.out.printf("%s: %d trechos%n", source.title(), documentChunks.size());
        }

        Path output = Path.of("data", "processed", "owasp-chunks.jsonl");
        Files.createDirectories(output.getParent());
        List<String> lines = chunks.stream().map(objectMapper::writeValueAsString).toList();
        Files.write(output, lines, StandardCharsets.UTF_8);
        System.out.printf("Total: %d trechos em %s%n", chunks.size(), output.toAbsolutePath());
    }

    private String download(OwaspSource source) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(source.downloadUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SecDocs-RAG-learning-project")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Falha ao baixar " + source.id() + ": HTTP " + response.statusCode());
            }
            byte[] bytes = body.readNBytes(MAX_DOCUMENT_BYTES + 1);
            if (bytes.length > MAX_DOCUMENT_BYTES) {
                throw new IOException("Documento maior que o limite: " + source.id());
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
