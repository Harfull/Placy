package net.kyver.placy.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.kyver.placy.service.archive.ArchiveProcessor;
import net.kyver.placy.service.file.FileTypeDetector;
import net.kyver.placy.service.file.StreamProcessor;
import net.kyver.placy.service.image.ImageProcessor;
import net.kyver.placy.service.document.DocumentProcessor;
import net.kyver.placy.util.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

@Service
public class FileTransformService {
    private final FileTypeDetector fileTypeDetector;
    private final StreamProcessor streamProcessor;
    private final ArchiveProcessor archiveProcessor;
    private final ImageProcessor imageProcessor;
    private final DocumentProcessor documentProcessor;
    private final Gson gson;

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
    }

    public byte[] transformFile(MultipartFile file, Map<String, String> placeholders) throws IOException {
        String filename = file.getOriginalFilename();
        Logger.debug("Processing file: " + filename);

        try {
            byte[] fileBytes = file.getBytes();
            String fileType = determineFileType(filename);

            return switch (fileType.toLowerCase()) {
                case "archive" -> {
                    Logger.debug("Processing as archive: " + filename);
                    yield archiveProcessor.processArchive(fileBytes, filename, placeholders);
                }
                case "image" -> {
                    Logger.debug("Processing as image: " + filename);
                    yield imageProcessor.processImage(fileBytes, filename, placeholders);
                }
                case "document" -> {
                    Logger.debug("Processing as document: " + filename);
                    yield documentProcessor.processDocument(fileBytes, filename, placeholders);
                }
                default -> {
                    Logger.debug("Processing as text: " + filename);
                    yield streamProcessor.transformTextStream(fileBytes, placeholders);
                }
            };
        } catch (Exception e) {
            Logger.error("Processing failed for " + filename + ": " + e.getMessage());
            throw new IOException("Failed to process file: " + filename, e);
        }
    }

    private String determineFileType(String filename) {
        if (filename == null) {
            return "text";
        }

        String extension = getFileExtension(filename).toLowerCase();

        if (extension.matches("zip|jar|war|ear")) {
            return "archive";
        }

        if (extension.matches("jpg|jpeg|png|gif|bmp|tiff|webp")) {
            return "image";
        }

        if (extension.matches("pdf|doc|docx|xls|xlsx|ppt|pptx")) {
            return "document";
        }

        return "text";
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex == -1 ? "" : filename.substring(lastDotIndex + 1);
    }

    public boolean isSupportedFileType(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }

        String extension = getFileExtension(filename).toLowerCase();

        return extension.matches("txt|json|xml|html|css|js|java|kt|py|yaml|yml|properties|md|" +
                                "zip|jar|war|ear|" +
                                "jpg|jpeg|png|gif|bmp|tiff|webp|" +
                                "pdf|doc|docx|xls|xlsx|ppt|pptx");
    }

    public Map<String, String> parsePlaceholders(String placeholdersJson) {
        try {
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> placeholders = gson.fromJson(placeholdersJson, type);
            return placeholders != null ? placeholders : new HashMap<>();
        } catch (Exception e) {
            Logger.warn("Failed to parse placeholders JSON: " + e.getMessage());
            return new HashMap<>();
        }
    }
}
