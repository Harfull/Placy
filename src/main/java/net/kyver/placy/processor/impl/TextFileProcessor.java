package net.kyver.placy.processor.impl;

import net.kyver.placy.core.PlaceholderEngine;
import net.kyver.placy.core.ProcessingResult;
import net.kyver.placy.core.ValidationResult;
import net.kyver.placy.processor.FileProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@Component
public class TextFileProcessor implements FileProcessor {
    private static final Logger logger = LoggerFactory.getLogger(TextFileProcessor.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "java", "kt", "py", "js", "ts", "cpp", "c", "h", "hpp", "cs", "go", "rs", "php", "rb", "swift",
            "html", "htm", "css", "scss", "sass", "jsx", "tsx", "vue",
            "json", "xml", "yaml", "yml", "toml", "ini", "conf", "config", "properties",
            "md", "txt", "rst", "adoc", "tex",
            "sh", "bash", "bat", "ps1", "sql", "hbs", "mustache", "jinja", "twig",
            "log", "csv", "tsv"
    );

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
        "text/plain", "text/html", "text/css", "text/javascript", "text/xml",
        "application/json", "application/xml", "application/javascript",
        "application/x-yaml", "application/x-toml"
    );

    private final PlaceholderEngine engine;

    public TextFileProcessor() {
        this.engine = new PlaceholderEngine();
        logger.debug("TextFileProcessor initialized with {} supported extensions", SUPPORTED_EXTENSIONS.size());
    }

    @Override
    public ProcessingResult process(InputStream input,
                                  OutputStream output,
                                  Map<String, String> placeholders,
                                  String filename) {

        logger.debug("Processing text file: {}", filename);

        try {
            ProcessingResult result = engine.processStream(input, output, placeholders, StandardCharsets.UTF_8);

            logger.debug("Text file processing completed: {}", result);
            return result;

        } catch (Exception e) {
            logger.error("Failed to process text file {}: {}", filename, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public Set<String> getSupportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }

    @Override
    public boolean canProcess(String filename, String mimeType) {
        if (filename != null) {
            String extension = getFileExtension(filename).toLowerCase();
            if (SUPPORTED_EXTENSIONS.contains(extension)) {
                return true;
            }
        }

        if (mimeType != null) {
            if (SUPPORTED_MIME_TYPES.contains(mimeType.toLowerCase()) ||
                mimeType.toLowerCase().startsWith("text/")) {
                return true;
            }
        }

        return false;
    }

    @Override
    public ValidationResult validate(String filename, Map<String, String> placeholders) {
        ValidationResult result = engine.validatePlaceholders(placeholders);

        if (filename != null && filename.toLowerCase().endsWith(".json")) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String value = entry.getValue();
                if (value != null && (value.contains("\"") || value.contains("\n") || value.contains("\r"))) {
                    result.addWarning("Placeholder value may break JSON syntax: " + entry.getKey());
                }
            }
        }

        return result;
    }

    @Override
    public String getProcessorName() {
        return "TextFileProcessor";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public long estimateMemoryUsage(long fileSize) {
        return fileSize / 10;
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot + 1);
    }
}
