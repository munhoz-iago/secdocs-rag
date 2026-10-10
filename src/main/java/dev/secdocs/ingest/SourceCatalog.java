package dev.secdocs.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class SourceCatalog {

    public List<OwaspSource> load() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/owasp-sources.properties")) {
            if (input == null) {
                throw new IOException("Catálogo de fontes OWASP não encontrado");
            }
            properties.load(input);
        }

        return Arrays.stream(required(properties, "sources").split(","))
                .map(String::trim)
                .map(id -> new OwaspSource(id, required(properties, id + ".title"),
                        required(properties, id + ".repository"), required(properties, id + ".path"),
                        required(properties, id + ".revision"), required(properties, id + ".license")))
                .toList();
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Campo ausente no catálogo: " + key);
        }
        return value.trim();
    }
}
