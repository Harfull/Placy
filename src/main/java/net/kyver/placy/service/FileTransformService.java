package net.kyver.placy.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.kyver.placy.service.archive.ArchiveProcessor;
import net.kyver.placy.service.file.FileTypeDetector;
import net.kyver.placy.service.file.StreamProcessor;
import net.kyver.placy.service.image.ImageProcessor;
import net.kyver.placy.service.document.DocumentProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class FileTransformService {
    private static final Logger logger = LoggerFactory.getLogger(FileTransformService.class);

    private final FileTypeDetector fileTypeDetector;
    private final StreamProcessor streamProcessor;
    private final ArchiveProcessor archiveProcessor;
    private final ImageProcessor imageProcessor;
    private final DocumentProcessor documentProcessor;
    private final Gson gson;

    private final ExecutorService executorService;

    @Autowired
    public FileTransformService(FileTypeDetector fileTypeDetector,
                              StreamProcessor streamProcessor,
                              ArchiveProcessor archiveProcessor,
                              ImageProcessor imageProcessor,
                              DocumentProcessor documentProcessor) {
        this.fileTypeDetector = fileTypeDetector;
        this.streamProcessor = streamProcessor;
        this.archiveProcessor = archiveProcessor;
        this.imageProcessor = imageProcessor;
        this.documentProcessor = documentProcessor;
        this.gson = new Gson();

        this.executorService = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2)
        );

        logger.info("FileTransformService initialized with comprehensive file type support");
    }

    public byte[] transformFile(MultipartFile file, Map<String, String> placeholders) throws IOException {
        String filename = Objects.requireNonNull(file.getOriginalFilename(), "File must have a name");
        long fileSize = file.getSize();

        logger.info("Transforming file: {} (size: {} bytes) with {} placeholders",
                   filename, fileSize, placeholders.size());

        if (placeholders.isEmpty()) {
            logger.debug("No placeholders provided, returning original file");
            return file.getBytes();
        }

        byte[] fileBytes = file.getBytes();

        return routeToProcessor(fileBytes, filename, placeholders);
    }

    private byte[] routeToProcessor(byte[] fileBytes, String filename, Map<String, String> placeholders) throws IOException {
        String extension = fileTypeDetector.getFileExtension(filename);

        if (fileTypeDetector.isArchiveFile(filename)) {
            logger.debug("Processing as archive file: {}", extension);
            return archiveProcessor.processArchive(fileBytes, filename, placeholders);
        }

        if (fileTypeDetector.getImageExtensions().contains(extension)) {
            logger.debug("Processing as image file: {}", extension);
            return imageProcessor.processImage(fileBytes, filename, placeholders);
        }

        if (documentProcessor.isSupportedDocument(filename)) {
            logger.debug("Processing as document file: {}", extension);
            return documentProcessor.processDocument(fileBytes, filename, placeholders);
        }

        if (fileTypeDetector.isTextFile(filename, fileBytes)) {
            logger.debug("Processing as text file: {}", extension);
            return streamProcessor.transformTextStream(fileBytes, placeholders);
        }

        String supportedTypes = String.join(", ", fileTypeDetector.getAllSupportedExtensions());
        throw new IllegalArgumentException(
            String.format("Unsupported file type: %s. Supported types: %s", extension, supportedTypes));
    }

    public CompletableFuture<byte[]> transformFileAsync(MultipartFile file, Map<String, String> placeholders) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return transformFile(file, placeholders);
            } catch (IOException e) {
                throw new RuntimeException("Failed to transform file asynchronously", e);
            }
        }, executorService);
    }

    public Map<String, String> parsePlaceholders(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new HashMap<>();
        }

        try {
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> placeholders = gson.fromJson(json, type);

            if (placeholders == null) {
                return new HashMap<>();
            }

            placeholders.entrySet().removeIf(entry -> {
                if (entry.getKey() == null || entry.getValue() == null) {
                    logger.warn("Removing null placeholder entry: {} -> {}", entry.getKey(), entry.getValue());
                    return true;
                }
                return false;
            });

            logger.debug("Parsed {} valid placeholders", placeholders.size());
            return placeholders;

        } catch (Exception e) {
            logger.error("Failed to parse placeholders JSON: {}", json, e);
            throw new IllegalArgumentException("Invalid JSON format for placeholders", e);
        }
    }

    public boolean isSupportedFileType(String filename) {
        return fileTypeDetector.isSupportedFileType(filename);
    }

    public Map<String, Object> getProcessingStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("supportedTextExtensions", fileTypeDetector.getTextExtensions().size());
        stats.put("supportedArchiveExtensions", fileTypeDetector.getArchiveExtensions().size());
        stats.put("supportedImageExtensions", fileTypeDetector.getImageExtensions().size());
        stats.put("supportedDocumentExtensions", fileTypeDetector.getDocumentExtensions().size());
        stats.put("totalSupportedExtensions", fileTypeDetector.getAllSupportedExtensions().size());
        stats.put("archiveFormats", archiveProcessor.getSupportedFormats().size());
        stats.put("activeThreads", executorService instanceof java.util.concurrent.ThreadPoolExecutor tpe ?
                 tpe.getActiveCount() : 0);
        return stats;
    }

    public Map<String, Object> getSupportedFileTypes() {
        Map<String, Object> supportedTypes = new HashMap<>();
        supportedTypes.put("text", fileTypeDetector.getTextExtensions());
        supportedTypes.put("archives", fileTypeDetector.getArchiveExtensions());
        supportedTypes.put("images", fileTypeDetector.getImageExtensions());
        supportedTypes.put("documents", fileTypeDetector.getDocumentExtensions());
        supportedTypes.put("other", fileTypeDetector.getOtherExtensions());
        supportedTypes.put("archiveFormats", archiveProcessor.getSupportedFormats());
        return supportedTypes;
    }

    public Map<String, Object> getFileTypeInfo(String filename) {
        Map<String, Object> info = new HashMap<>();
        String extension = fileTypeDetector.getFileExtension(filename);

        info.put("extension", extension);
        info.put("isSupported", fileTypeDetector.isSupportedFileType(filename));
        info.put("isArchive", fileTypeDetector.isArchiveFile(filename));
        info.put("isText", fileTypeDetector.getTextExtensions().contains(extension));
        info.put("isImage", fileTypeDetector.getImageExtensions().contains(extension));
        info.put("isDocument", documentProcessor.isSupportedDocument(filename));
        info.put("supportsMetadataEditing", imageProcessor.supportsMetadataEditing(filename));

        return info;
    }

    public void clearCaches() {
        fileTypeDetector.clearCaches();
        archiveProcessor.clearCache();
        imageProcessor.clearCache();
        documentProcessor.clearCache();
        logger.info("All processor caches cleared");
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("FileTransformService shutdown complete");
    }
}
