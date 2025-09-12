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
public class DocumentFileProcessor implements FileProcessor {
    private static final Logger logger = LoggerFactory.getLogger(DocumentFileProcessor.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp"
    );

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation"
    );

    @Override
    public ProcessingResult process(InputStream input,
                                  OutputStream output,
                                  Map<String, String> placeholders,
                                  String filename) {

        logger.debug("Processing document file: {}", filename);

        try {
            String extension = getFileExtension(filename).toLowerCase();

            if ("pdf".equals(extension)) {
                return processPdfDocument(input, output, placeholders, filename);
            } else if (isOfficeDocument(extension)) {
                return processOfficeDocument(input, output, placeholders, filename);
            } else {
                long bytesTransferred = input.transferTo(output);
                return new ProcessingResult(bytesTransferred, 0, 0);
            }

        } catch (Exception e) {
            logger.error("Failed to process document file {}: {}", filename, e.getMessage(), e);
            throw new RuntimeException("Document processing failed", e);
        }
    }

    private ProcessingResult processPdfDocument(InputStream input,
                                              OutputStream output,
                                              Map<String, String> placeholders,
                                              String filename) {
        try {
            long bytesTransferred = input.transferTo(output);
            logger.debug("PDF document copied without processing: {} bytes", bytesTransferred);
            return new ProcessingResult(bytesTransferred, 0, 0);
        } catch (Exception e) {
            throw new RuntimeException("PDF processing failed", e);
        }
    }

    private ProcessingResult processOfficeDocument(InputStream input,
                                                 OutputStream output,
                                                 Map<String, String> placeholders,
                                                 String filename) {
        try {
            long bytesTransferred = input.transferTo(output);
            logger.debug("Office document copied without processing: {} bytes", bytesTransferred);
            return new ProcessingResult(bytesTransferred, 0, 0);
        } catch (Exception e) {
            throw new RuntimeException("Office document processing failed", e);
        }
    }

    private boolean isOfficeDocument(String extension) {
        return Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp")
                  .contains(extension);
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

        if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType.toLowerCase())) {
            return true;
        }

        return false;
    }

    @Override
    public ValidationResult validate(String filename, Map<String, String> placeholders) {
        ValidationResult result = new ValidationResult();

        if (placeholders.size() > 0) {
            result.addWarning("Document content processing not yet fully implemented - limited placeholder support");
        }

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.length() > 10000) {
                result.addWarning("Large placeholder value may impact document processing: " + entry.getKey());
            }
        }

        return result;
    }

    @Override
    public String getProcessorName() {
        return "DocumentFileProcessor";
    }

    @Override
    public int getPriority() {
        return 250;
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    @Override
    public long estimateMemoryUsage(long fileSize) {
        return Math.min(fileSize * 3, 100 * 1024 * 1024);
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot + 1);
    }
}
