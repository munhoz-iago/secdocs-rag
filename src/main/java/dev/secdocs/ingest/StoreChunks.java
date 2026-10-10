package dev.secdocs.ingest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "secdocs", name = "store", havingValue = "true")
public class StoreChunks implements ApplicationRunner {

    private static final String UPSERT = """
            INSERT INTO document_chunks
              (id, source_id, source_title, section, source_url, revision, license, content)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              source_id = EXCLUDED.source_id,
              source_title = EXCLUDED.source_title,
              section = EXCLUDED.section,
              source_url = EXCLUDED.source_url,
              revision = EXCLUDED.revision,
              license = EXCLUDED.license,
              content = EXCLUDED.content,
              embedding = CASE WHEN document_chunks.content = EXCLUDED.content
                                    AND document_chunks.section = EXCLUDED.section
                                    AND document_chunks.source_title = EXCLUDED.source_title
                               THEN document_chunks.embedding ELSE NULL END,
              embedding_model = CASE WHEN document_chunks.content = EXCLUDED.content
                                          AND document_chunks.section = EXCLUDED.section
                                          AND document_chunks.source_title = EXCLUDED.source_title
                                     THEN document_chunks.embedding_model ELSE NULL END,
              embedded_at = CASE WHEN document_chunks.content = EXCLUDED.content
                                      AND document_chunks.section = EXCLUDED.section
                                      AND document_chunks.source_title = EXCLUDED.source_title
                                 THEN document_chunks.embedded_at ELSE NULL END,
              imported_at = now()
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path input = Path.of("data", "processed", "owasp-chunks.jsonl");
        if (!Files.isRegularFile(input)) {
            throw new IllegalStateException("Arquivo de trechos não encontrado. Rode a ingestão antes: " + input);
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                DocumentChunk chunk = objectMapper.readValue(line, DocumentChunk.class);
                chunks.add(chunk);
            }
        }
        List<OwaspSource> sources = new SourceCatalog().load();
        validatePreflight(chunks, sources);
        String[] catalogIds = sources.stream().map(OwaspSource::id).toArray(String[]::new);

        String url = requiredEnv("SECDOCS_DB_URL");
        String user = requiredEnv("SECDOCS_DB_USER");
        String password = requiredEnv("SECDOCS_DB_PASSWORD");
        Flyway.configure().dataSource(url, user, password).load().migrate();

        Map<String, List<String>> idsBySource = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement upsert = connection.prepareStatement(UPSERT)) {
                    for (DocumentChunk chunk : chunks) {
                        idsBySource.computeIfAbsent(chunk.sourceId(), ignored -> new ArrayList<>()).add(chunk.id());
                        upsert.setString(1, chunk.id());
                        upsert.setString(2, chunk.sourceId());
                        upsert.setString(3, chunk.sourceTitle());
                        upsert.setString(4, chunk.section());
                        upsert.setString(5, chunk.sourceUrl());
                        upsert.setString(6, chunk.revision());
                        upsert.setString(7, chunk.license());
                        upsert.setString(8, chunk.text());
                        upsert.addBatch();
                    }
                    upsert.executeBatch();
                }
                try (PreparedStatement prune = connection.prepareStatement(
                        "DELETE FROM document_chunks WHERE source_id = ? AND NOT (id = ANY (?))")) {
                    for (var entry : idsBySource.entrySet()) {
                        prune.setString(1, entry.getKey());
                        prune.setArray(2, connection.createArrayOf("text", entry.getValue().toArray(String[]::new)));
                        prune.executeUpdate();
                    }
                }
                try (PreparedStatement pruneRemovedSources = connection.prepareStatement(
                        "DELETE FROM document_chunks WHERE NOT (source_id = ANY (?))")) {
                    pruneRemovedSources.setArray(1, connection.createArrayOf("text", catalogIds));
                    pruneRemovedSources.executeUpdate();
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }

            try (PreparedStatement count = connection.prepareStatement("SELECT count(*) FROM document_chunks");
                 ResultSet result = count.executeQuery()) {
                result.next();
                System.out.printf("%d trechos enviados; %d trechos no banco%n", chunks.size(), result.getLong(1));
            }
        }
    }

    static void validatePreflight(List<DocumentChunk> chunks, List<OwaspSource> sources) {
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Arquivo de trechos está vazio; rode a ingestão novamente");
        }
        Map<String, OwaspSource> catalog = new LinkedHashMap<>();
        for (OwaspSource source : sources) {
            if (catalog.putIfAbsent(source.id(), source) != null) {
                throw new IllegalStateException("Fonte duplicada no catálogo: " + source.id());
            }
        }
        if (catalog.isEmpty()) {
            throw new IllegalStateException("Catálogo de fontes vazio");
        }

        Set<String> seenIds = new HashSet<>();
        Set<String> seenSources = new HashSet<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk.id() == null || chunk.id().isBlank()) {
                throw new IllegalStateException("Trecho sem id: " + chunk.sourceId());
            }
            if (!seenIds.add(chunk.id())) {
                throw new IllegalStateException("ID de trecho duplicado: " + chunk.id());
            }
            if (chunk.sourceId() == null || chunk.sourceId().isBlank()) {
                throw new IllegalStateException("Trecho sem sourceId: " + chunk.id());
            }
            OwaspSource source = catalog.get(chunk.sourceId());
            if (source == null) {
                throw new IllegalStateException("Fonte fora do catálogo atual; rode a ingestão novamente: "
                        + chunk.sourceId());
            }
            if (!source.revision().equals(chunk.revision()) || !source.sourceUrl().equals(chunk.sourceUrl())
                    || !source.title().equals(chunk.sourceTitle()) || !source.license().equals(chunk.license())) {
                throw new IllegalStateException("Metadados desatualizados para " + chunk.sourceId()
                        + "; rode a ingestão novamente");
            }
            seenSources.add(chunk.sourceId());
        }
        if (!seenSources.containsAll(catalog.keySet())) {
            Set<String> missing = new LinkedHashSet<>(catalog.keySet());
            missing.removeAll(seenSources);
            throw new IllegalStateException("Fontes ausentes no arquivo de trechos; rode a ingestão novamente: "
                    + missing);
        }
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variável de ambiente obrigatória: " + key);
        }
        return value;
    }
}
