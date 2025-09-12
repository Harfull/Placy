package net.kyver.placy.core.replacement;

import net.kyver.placy.core.PlaceholderProcessingException;
import net.kyver.placy.core.ProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.Map;

public class StreamingReplacementStrategy implements ReplacementStrategy {
    private static final Logger logger = LoggerFactory.getLogger(StreamingReplacementStrategy.class);

    private static final int DEFAULT_BUFFER_SIZE = 2 * 1024 * 1024;
    private static final int MIN_BUFFER_SIZE = 256 * 1024;
    private static final int MAX_OVERLAP_SIZE = 64 * 1024;

    private final int bufferSize;
    private final int overlapSize;
    private final ThreadLocal<CharBuffer> charBufferCache;
    private final ThreadLocal<ByteBuffer> byteBufferCache;

    public StreamingReplacementStrategy() {
        this(DEFAULT_BUFFER_SIZE);
    }

    public StreamingReplacementStrategy(int bufferSize) {
        this.bufferSize = Math.max(MIN_BUFFER_SIZE, bufferSize);
        this.overlapSize = Math.min(MAX_OVERLAP_SIZE, this.bufferSize / 8);

        this.charBufferCache = ThreadLocal.withInitial(() -> CharBuffer.allocate(this.bufferSize + this.overlapSize));
        this.byteBufferCache = ThreadLocal.withInitial(() -> ByteBuffer.allocate(this.bufferSize));

        logger.debug("High-performance StreamingReplacementStrategy initialized: buffer={}MB, overlap={}KB",
                    this.bufferSize / (1024 * 1024), this.overlapSize / 1024);
    }

    @Override
    public ProcessingResult replace(InputStream input,
                                  OutputStream output,
                                  Map<String, String> placeholders,
                                  Charset charset) {

        if (placeholders.isEmpty()) {
            return copyStreamDirect(input, output);
        }

        Map<String, String> sortedPlaceholders = placeholders.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getKey().length(), e1.getKey().length()))
            .collect(java.util.LinkedHashMap::new,
                     (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                     java.util.LinkedHashMap::putAll);

        try (BufferedInputStream bufferedInput = new BufferedInputStream(input, bufferSize);
             BufferedOutputStream bufferedOutput = new BufferedOutputStream(output, bufferSize)) {

            return processStreamOptimized(bufferedInput, bufferedOutput, sortedPlaceholders, charset);

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

        if (content.length > bufferSize) {
            try (ByteArrayInputStream input = new ByteArrayInputStream(content);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(content.length + (content.length >> 2))) {

                replace(input, output, placeholders, charset);
                return output.toByteArray();

            } catch (IOException e) {
                throw new PlaceholderProcessingException("Byte array replacement failed", e, "BYTE_ARRAY_ERROR");
            }
        }

        String text = new String(content, charset);
        String processed = processPlaceholdersOptimized(text, placeholders);
        return processed.getBytes(charset);
    }

    private ProcessingResult processStreamOptimized(BufferedInputStream input,
                                                   BufferedOutputStream output,
                                                   Map<String, String> placeholders,
                                                   Charset charset) throws IOException {

        long totalBytesProcessed = 0;
        long totalReplacements = 0;

        CharsetDecoder decoder = charset.newDecoder();

        ByteBuffer inputBuffer = byteBufferCache.get();
        CharBuffer charBuffer = charBufferCache.get();

        inputBuffer.clear();
        charBuffer.clear();

        StringBuilder processingBuffer = new StringBuilder(bufferSize + overlapSize);
        String overlap = "";

        byte[] readBuffer = new byte[bufferSize];
        int bytesRead;

        while ((bytesRead = input.read(readBuffer)) != -1) {
            totalBytesProcessed += bytesRead;

            inputBuffer.clear();
            inputBuffer.put(readBuffer, 0, bytesRead);
            inputBuffer.flip();

            charBuffer.clear();
            CoderResult result = decoder.decode(inputBuffer, charBuffer, false);

            if (result.isError()) {
                String chunk = new String(readBuffer, 0, bytesRead, charset);
                processingBuffer.setLength(0);
                processingBuffer.append(overlap).append(chunk);
            } else {
                charBuffer.flip();
                processingBuffer.setLength(0);
                processingBuffer.append(overlap).append(charBuffer);
            }

            String content = processingBuffer.toString();
            String processed = processPlaceholdersOptimized(content, placeholders);
            totalReplacements += countReplacementsDelta(content, processed, placeholders);

            input.mark(1);
            int peek = input.read();
            boolean isLastChunk = (peek == -1);
            if (!isLastChunk) {
                input.reset();
            }

            if (!isLastChunk && processed.length() > overlapSize) {
                int writeLength = processed.length() - overlapSize;
                output.write(processed.substring(0, writeLength).getBytes(charset));
                overlap = processed.substring(writeLength);
            } else {
                output.write(processed.getBytes(charset));
                overlap = "";
            }
        }

        if (!overlap.isEmpty()) {
            String processed = processPlaceholdersOptimized(overlap, placeholders);
            totalReplacements += countReplacementsDelta(overlap, processed, placeholders);
            output.write(processed.getBytes(charset));
        }

        output.flush();
        return new ProcessingResult(totalBytesProcessed, totalReplacements, placeholders.size());
    }

    private String processPlaceholdersOptimized(String content, Map<String, String> placeholders) {
        if (placeholders.isEmpty()) return content;

        String result = content;

        if (content.length() > 50000) {
            StringBuilder sb = new StringBuilder(content);

            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String placeholder = entry.getKey();
                String value = entry.getValue();

                if (value != null) {
                    replaceInStringBuilder(sb, placeholder, value);
                }
            }

            return sb.toString();
        } else {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String placeholder = entry.getKey();
                String value = entry.getValue();

                if (value != null && result.contains(placeholder)) {
                    result = result.replace(placeholder, value);
                }
            }

            return result;
        }
    }

    private void replaceInStringBuilder(StringBuilder sb, String placeholder, String replacement) {
        int index = 0;
        int placeholderLength = placeholder.length();
        int replacementLength = replacement.length();

        while ((index = sb.indexOf(placeholder, index)) != -1) {
            sb.replace(index, index + placeholderLength, replacement);
            index += replacementLength;
        }
    }

    private long countReplacementsDelta(String original, String processed, Map<String, String> placeholders) {
        if (original.equals(processed)) return 0;

        long count = 0;
        for (String placeholder : placeholders.keySet()) {
            int originalCount = countOccurrencesOptimized(original, placeholder);
            int processedCount = countOccurrencesOptimized(processed, placeholder);
            count += (originalCount - processedCount);
        }
        return count;
    }

    private int countOccurrencesOptimized(String text, String substring) {
        if (text.isEmpty() || substring.isEmpty()) return 0;

        int count = 0;
        int index = 0;
        int substringLength = substring.length();

        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substringLength;
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
        return "HighPerformanceStreamingReplacementStrategy";
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
        return bufferSize;
    }
}
