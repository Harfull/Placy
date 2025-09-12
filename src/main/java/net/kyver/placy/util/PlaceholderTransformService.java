package net.kyver.placy.util;

import net.kyver.placy.core.ProcessingResult;
import net.kyver.placy.core.ValidationResult;
import net.kyver.placy.processor.FileProcessor;
import net.kyver.placy.processor.FileProcessorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class PlaceholderTransformService {
    private static final Logger logger = LoggerFactory.getLogger(PlaceholderTransformService.class);

    private final FileProcessorRegistry processorRegistry;
    private final PerformanceProfiler profiler;
    private final Executor asyncExecutor;

    @Autowired
    public PlaceholderTransformService(FileProcessorRegistry processorRegistry,
                                     Executor taskExecutor) {
        this.processorRegistry = processorRegistry;
        this.profiler = new PerformanceProfiler();
        this.asyncExecutor = taskExecutor;
    }

    public TransformationResult transformFile(MultipartFile file, Map<String, String> placeholders) {
        String filename = file.getOriginalFilename();
        logger.info("Transforming file: {} (size: {} bytes, placeholders: {})",
                   filename, file.getSize(), placeholders.size());

        long startTime = System.nanoTime();

        try {
            ValidationResult validation = validateTransformation(filename, file.getContentType(), placeholders);
            if (!validation.isValid()) {
                return TransformationResult.error(filename, "Validation failed: " + validation.getErrors());
            }

            FileProcessor processor = processorRegistry.findProcessor(filename, file.getContentType());
            if (processor == null) {
                return TransformationResult.error(filename, "No processor found for file type");
            }

            logger.debug("Using processor: {} for file: {}", processor.getProcessorName(), filename);

            try (InputStream input = file.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {

                ProcessingResult result = processor.process(input, output, placeholders, filename);

                return TransformationResult.success(filename, output.toByteArray(), result);
            }

        } catch (Exception e) {
            long duration = System.nanoTime() - startTime;
            logger.error("File transformation failed: {} after {:.2f}ms: {}",
                        filename, duration / 1_000_000.0, e.getMessage(), e);

            return TransformationResult.error(filename, "Processing failed: " + e.getMessage());
        }
    }

    public CompletableFuture<TransformationResult> transformFileAsync(MultipartFile file,
                                                                     Map<String, String> placeholders) {
        return CompletableFuture.supplyAsync(() -> transformFile(file, placeholders), asyncExecutor);
    }

    public ValidationResult validateTransformation(String filename, String mimeType, Map<String, String> placeholders) {
        return processorRegistry.validateFile(filename, mimeType, placeholders);
    }

    public boolean isFileSupported(String filename, String mimeType) {
        return processorRegistry.findProcessor(filename, mimeType) != null;
    }

    public Map<String, Object> getPerformanceMetrics() {
        return Map.of(
            "totalOperations", profiler.getTotalOperations(),
            "totalBytesProcessed", profiler.getTotalBytesProcessed(),
            "averageProcessingTimeMs", profiler.getAverageProcessingTimeNanos() / 1_000_000.0,
            "averageThroughputMBps", profiler.getAverageThroughputBytesPerSecond() / (1024.0 * 1024.0),
            "maxThroughputMBps", profiler.getMaxThroughputBytesPerSecond() / (1024.0 * 1024.0),
            "supportedExtensions", processorRegistry.getAllSupportedExtensions().size(),
            "cacheStats", processorRegistry.getCacheStats()
        );
    }

    public String generatePerformanceReport() {
        return profiler.generateReport();
    }

    public void resetPerformanceMetrics() {
        profiler.reset();
        processorRegistry.clearCache();
        logger.info("Performance metrics reset");
    }

    public java.util.Set<String> getSupportedExtensions() {
        return processorRegistry.getAllSupportedExtensions();
    }

    public java.util.Set<String> getSupportedMimeTypes() {
        return processorRegistry.getAllSupportedMimeTypes();
    }
}
