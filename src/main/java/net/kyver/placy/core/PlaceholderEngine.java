package net.kyver.placy.core;

import net.kyver.placy.core.replacement.ReplacementStrategy;
import net.kyver.placy.core.replacement.StreamingReplacementStrategy;
import net.kyver.placy.util.PerformanceProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class PlaceholderEngine {
    private static final Logger logger = LoggerFactory.getLogger(PlaceholderEngine.class);

    private final ReplacementStrategy replacementStrategy;
    private final Charset defaultCharset;
    private final PerformanceProfiler profiler;
    private final Map<String, Object> metrics;

    public PlaceholderEngine() {
        this(new StreamingReplacementStrategy());
    }

    public PlaceholderEngine(@NonNull ReplacementStrategy replacementStrategy) {
        this.replacementStrategy = Objects.requireNonNull(replacementStrategy, "Strategy cannot be null");
        this.defaultCharset = StandardCharsets.UTF_8;
        this.profiler = new PerformanceProfiler();
        this.metrics = new ConcurrentHashMap<>();

        logger.debug("PlaceholderEngine initialized with strategy: {}",
                    replacementStrategy.getClass().getSimpleName());
    }

    public ProcessingResult processStream(@NonNull InputStream input,
                                        @NonNull OutputStream output,
                                        @NonNull Map<String, String> placeholders,
                                        @NonNull Charset charset) {
        Objects.requireNonNull(input, "Input stream cannot be null");
        Objects.requireNonNull(output, "Output stream cannot be null");
        Objects.requireNonNull(placeholders, "Placeholders cannot be null");
        Objects.requireNonNull(charset, "Charset cannot be null");

        if (placeholders.isEmpty()) {
            logger.debug("No placeholders provided, performing direct copy");
            return copyStream(input, output);
        }

        long startTime = System.nanoTime();

        try {
            logger.debug("Starting placeholder processing for {} placeholders", placeholders.size());

            ProcessingResult result = replacementStrategy.replace(
                input, output, placeholders, charset);

            long duration = System.nanoTime() - startTime;
            result.setProcessingTimeNanos(duration);

            profiler.recordProcessing(duration, result.getBytesProcessed(), placeholders.size());
            updateMetrics(result);

            logger.debug("Placeholder processing completed in {} ms, {} bytes processed, {} replacements made",
                        duration / 1_000_000, result.getBytesProcessed(), result.getReplacementCount());

            return result;

        } catch (Exception e) {
            long duration = System.nanoTime() - startTime;
            logger.error("Placeholder processing failed after {} ms: {}", duration / 1_000_000, e.getMessage(), e);
            throw new PlaceholderProcessingException("Failed to process placeholders", e);
        }
    }

    public ProcessingResult processStream(@NonNull InputStream input,
                                        @NonNull OutputStream output,
                                        @NonNull Map<String, String> placeholders) {
        return processStream(input, output, placeholders, defaultCharset);
    }

    public byte[] processBytes(@NonNull byte[] content,
                             @NonNull Map<String, String> placeholders,
                             @NonNull Charset charset) {
        Objects.requireNonNull(content, "Content cannot be null");
        Objects.requireNonNull(placeholders, "Placeholders cannot be null");
        Objects.requireNonNull(charset, "Charset cannot be null");

        if (placeholders.isEmpty()) {
            return content.clone();
        }

        return replacementStrategy.replace(content, placeholders, charset);
    }

    public byte[] processBytes(@NonNull byte[] content, @NonNull Map<String, String> placeholders) {
        return processBytes(content, placeholders, defaultCharset);
    }

    public ValidationResult validatePlaceholders(@NonNull Map<String, String> placeholders) {
        Objects.requireNonNull(placeholders, "Placeholders cannot be null");

        ValidationResult result = new ValidationResult();

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key == null || key.trim().isEmpty()) {
                result.addError("Empty placeholder key found");
                continue;
            }

            if (value == null) {
                result.addWarning("Null value for placeholder: " + key);
            }

            if (value != null && value.contains(key)) {
                result.addWarning("Potential circular reference in placeholder: " + key);
            }
        }

        return result;
    }

    public Map<String, Object> getMetrics() {
        return Collections.unmodifiableMap(metrics);
    }

    public void resetMetrics() {
        metrics.clear();
        profiler.reset();
    }

    public ReplacementStrategy getReplacementStrategy() {
        return replacementStrategy;
    }

    private ProcessingResult copyStream(InputStream input, OutputStream output) {
        try {
            byte[] buffer = new byte[2 * 1024 * 1024];
            long totalBytes = 0;
            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            return new ProcessingResult(totalBytes, 0, 0);

        } catch (Exception e) {
            throw new PlaceholderProcessingException("Failed to copy stream", e);
        }
    }

    private void updateMetrics(ProcessingResult result) {
        metrics.merge("totalBytesProcessed", result.getBytesProcessed(), (a, b) -> (Long) a + (Long) b);
        metrics.merge("totalReplacements", result.getReplacementCount(), (a, b) -> (Long) a + (Long) b);
        metrics.merge("totalOperations", 1L, (a, b) -> (Long) a + (Long) b);

        Long avgProcessingTime = (Long) metrics.get("averageProcessingTimeNanos");
        if (avgProcessingTime == null) {
            metrics.put("averageProcessingTimeNanos", result.getProcessingTimeNanos());
        } else {
            Long totalOps = (Long) metrics.get("totalOperations");
            long newAvg = (avgProcessingTime * (totalOps - 1) + result.getProcessingTimeNanos()) / totalOps;
            metrics.put("averageProcessingTimeNanos", newAvg);
        }
    }
}
