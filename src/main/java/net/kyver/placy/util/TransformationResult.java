package net.kyver.placy.util;

import net.kyver.placy.core.ProcessingResult;

import java.util.List;

public class TransformationResult {

    private final String filename;
    private final boolean success;
    private final byte[] content;
    private final String errorMessage;
    private final ProcessingResult processingResult;

    private TransformationResult(String filename, boolean success, byte[] content,
                               String errorMessage, ProcessingResult processingResult) {
        this.filename = filename;
        this.success = success;
        this.content = content;
        this.errorMessage = errorMessage;
        this.processingResult = processingResult;
    }

    public static TransformationResult success(String filename, byte[] content, ProcessingResult processingResult) {
        return new TransformationResult(filename, true, content, null, processingResult);
    }

    public static TransformationResult error(String filename, String errorMessage) {
        return new TransformationResult(filename, false, null, errorMessage, null);
    }

    public String getFilename() {
        return filename;
    }

    public boolean isSuccess() {
        return success;
    }

    public byte[] getContent() {
        return content != null ? content.clone() : null;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public ProcessingResult getProcessingResult() {
        return processingResult;
    }

    public List<String> getWarnings() {
        return processingResult != null ? processingResult.getWarnings() : List.of();
    }

    public boolean hasWarnings() {
        return processingResult != null && processingResult.hasWarnings();
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("TransformationResult{filename='%s', success=true, size=%d bytes, %s}",
                               filename, content != null ? content.length : 0, processingResult);
        } else {
            return String.format("TransformationResult{filename='%s', success=false, error='%s'}",
                               filename, errorMessage);
        }
    }
}
