package net.kyver.placy.core.replacement;

import net.kyver.placy.core.PlaceholderProcessingException;
import net.kyver.placy.core.ProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Map;

public class StreamingReplacementStrategy implements ReplacementStrategy {
    private static final Logger logger = LoggerFactory.getLogger(StreamingReplacementStrategy.class);

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
    private static final int MIN_BUFFER_SIZE = 8 * 1024;
    private static final int MAX_OVERLAP_SIZE = 1024;

    private final int bufferSize;
    private final int overlapSize;

    public StreamingReplacementStrategy() {
        this(DEFAULT_BUFFER_SIZE);
    }

    public StreamingReplacementStrategy(int bufferSize) {
        this.bufferSize = Math.max(MIN_BUFFER_SIZE, bufferSize);
        this.overlapSize = Math.min(MAX_OVERLAP_SIZE, this.bufferSize / 4);

        logger.debug("StreamingReplacementStrategy initialized with buffer size: {}KB, overlap: {}B",
                    this.bufferSize / 1024, this.overlapSize);
    }

    @Override
    public ProcessingResult replace(InputStream input,
                                  OutputStream output,
                                  Map<String, String> placeholders,
                                  Charset charset) {

        if (placeholders.isEmpty()) {
            return copyStreamDirect(input, output);
        }

        try (BufferedInputStream bufferedInput = new BufferedInputStream(input, bufferSize);
             BufferedOutputStream bufferedOutput = new BufferedOutputStream(output, bufferSize)) {

            return processStreamChunked(bufferedInput, bufferedOutput, placeholders, charset);

        } catch (IOException e) {
            throw new PlaceholderProcessingException("Streaming replacement failed", e, "STREAM_IO_ERROR");
        }
    }

    @Override
    public byte[] replace(byte[] content,
                         Map<String, String> placeholders,
                         Charset charset) {

        if (placeholders.isEmpty()) {
            return content.clone();
        }

        try (ByteArrayInputStream input = new ByteArrayInputStream(content);
             ByteArrayOutputStream output = new ByteArrayOutputStream(content.length + (content.length / 4))) {

            ProcessingResult result = replace(input, output, placeholders, charset);
            return output.toByteArray();

        } catch (IOException e) {
            throw new PlaceholderProcessingException("Byte array replacement failed", e, "BYTE_ARRAY_ERROR");
        }
    }

    private ProcessingResult processStreamChunked(BufferedInputStream input,
                                                BufferedOutputStream output,
                                                Map<String, String> placeholders,
                                                Charset charset) throws IOException {

        long totalBytesProcessed = 0;
        long totalReplacements = 0;

        StringBuilder buffer = new StringBuilder(bufferSize + overlapSize);
        String overlap = "";

        byte[] readBuffer = new byte[bufferSize];
        int bytesRead;
        boolean isLastChunk = false;

        while ((bytesRead = input.read(readBuffer)) != -1) {
            String chunk = new String(readBuffer, 0, bytesRead, charset);
            buffer.setLength(0);
            buffer.append(overlap).append(chunk);

            String content = buffer.toString();
            totalBytesProcessed += bytesRead;

            String processed = processPlaceholders(content, placeholders);

            totalReplacements += countReplacements(content, processed, placeholders);

            // Check if this is the last chunk by trying to peek ahead
            input.mark(1);
            int nextByte = input.read();
            if (nextByte == -1) {
                isLastChunk = true;
            } else {
                input.reset();
            }

            if (!isLastChunk) {
                int writeLength = Math.max(0, processed.length() - overlapSize);
                if (writeLength > 0) {
                    output.write(processed.substring(0, writeLength).getBytes(charset));
                    overlap = processed.substring(writeLength);
                } else {
                    overlap = processed;
                }
            } else {
                output.write(processed.getBytes(charset));
                overlap = "";
                break;
            }
        }

        if (!overlap.isEmpty()) {
            output.write(overlap.getBytes(charset));
        }

        output.flush();

        return new ProcessingResult(totalBytesProcessed, totalReplacements, placeholders.size());
    }

    private String processPlaceholders(String content, Map<String, String> placeholders) {
        String result = content;

        for (Map.Entry<String, String> entry : placeholders.entrySet()
                .stream()
                .sorted((e1, e2) -> Integer.compare(e2.getKey().length(), e1.getKey().length()))
                .toList()) {
            String placeholder = entry.getKey();
            String value = entry.getValue();

            if (value != null && result.contains(placeholder)) {
                result = result.replace(placeholder, value);
            }
        }

        return result;
    }

    private long countReplacements(String original, String processed, Map<String, String> placeholders) {
        long count = 0;

        for (String placeholder : placeholders.keySet()) {
            int originalCount = countOccurrences(original, placeholder);
            int processedCount = countOccurrences(processed, placeholder);
            count += (originalCount - processedCount);
        }

        return count;
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;

        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }

        return count;
    }

    private ProcessingResult copyStreamDirect(InputStream input, OutputStream output) {
        try (BufferedInputStream bufferedInput = new BufferedInputStream(input, bufferSize);
             BufferedOutputStream bufferedOutput = new BufferedOutputStream(output, bufferSize)) {

            byte[] buffer = new byte[bufferSize];
            long totalBytes = 0;
            int bytesRead;

            while ((bytesRead = bufferedInput.read(buffer)) != -1) {
                bufferedOutput.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }

            bufferedOutput.flush();
            return new ProcessingResult(totalBytes, 0, 0);

        } catch (IOException e) {
            throw new PlaceholderProcessingException("Stream copy failed", e, "STREAM_COPY_ERROR");
        }
    }

    @Override
    public String getStrategyName() {
        return "StreamingReplacementStrategy";
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean supportsParallelProcessing() {
        return false;
    }

    @Override
    public long getRecommendedMinimumSize() {
        return bufferSize * 2;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getOverlapSize() {
        return overlapSize;
    }
}
