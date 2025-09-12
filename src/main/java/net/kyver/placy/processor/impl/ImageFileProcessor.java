package net.kyver.placy.processor.impl;

import net.kyver.placy.core.ProcessingResult;
import net.kyver.placy.core.ValidationResult;
import net.kyver.placy.processor.FileProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

@Component
public class ImageFileProcessor implements FileProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ImageFileProcessor.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "webp"
    );

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
        "image/jpeg", "image/png", "image/gif", "image/bmp",
        "image/tiff", "image/webp"
    );

    @Override
    public ProcessingResult process(InputStream input,
                                  OutputStream output,
                                  Map<String, String> placeholders,
                                  String filename) {

        logger.debug("Processing image file: {}", filename);

        try {
            long bytesTransferred = input.transferTo(output);

            logger.debug("Image file copied: {} bytes", bytesTransferred);
            return new ProcessingResult(bytesTransferred, 0, 0);

        } catch (Exception e) {
            logger.error("Failed to process image file {}: {}", filename, e.getMessage(), e);
            throw new RuntimeException("Image processing failed", e);
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

        if (mimeType != null && mimeType.toLowerCase().startsWith("image/")) {
            return SUPPORTED_MIME_TYPES.contains(mimeType.toLowerCase());
        }

        return false;
    }

    @Override
    public ValidationResult validate(String filename, Map<String, String> placeholders) {
        ValidationResult result = new ValidationResult();

        if (placeholders.size() > 0) {
            result.addWarning("Image metadata processing not yet implemented - placeholders will be ignored");
        }

        return result;
    }

    @Override
    public String getProcessorName() {
        return "ImageFileProcessor";
    }

    @Override
    public int getPriority() {
        return 300;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public long estimateMemoryUsage(long fileSize) {
        return Math.min(fileSize / 20, 5 * 1024 * 1024);
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot + 1);
    }
}
