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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
            Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

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

        try {
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
        } catch (Exception e) {
            logger.error("Error processing file {}: {}", filename, e.getMessage(), e);
            throw new IOException("Failed to process file: " + filename, e);
        }
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

    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            logger.info("FileTransformService executor shutdown");
        }
    }
}
