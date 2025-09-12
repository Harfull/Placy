package net.kyver.placy.processor;

import net.kyver.placy.core.ProcessingResult;
import net.kyver.placy.core.ValidationResult;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

public interface FileProcessor {

    ProcessingResult process(InputStream input,
                           OutputStream output,
                           Map<String, String> placeholders,
                           String filename);

    Set<String> getSupportedExtensions();

    Set<String> getSupportedMimeTypes();

    boolean canProcess(String filename, String mimeType);

    ValidationResult validate(String filename, Map<String, String> placeholders);

    String getProcessorName();

    int getPriority();

    boolean supportsStreaming();

    long estimateMemoryUsage(long fileSize);
}
