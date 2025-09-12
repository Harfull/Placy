package net.kyver.placy.core.replacement;

import net.kyver.placy.core.PlaceholderProcessingException;
import net.kyver.placy.core.ProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.*;

public class ParallelReplacementStrategy implements ReplacementStrategy {
    private static final Logger logger = LoggerFactory.getLogger(ParallelReplacementStrategy.class);

    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    private static final int MIN_CHUNK_SIZE = 64 * 1024;
    private static final int MAX_OVERLAP_SIZE = 1024;

    private final int chunkSize;
    private final int overlapSize;
    private final ForkJoinPool forkJoinPool;

    public ParallelReplacementStrategy() {
        this(DEFAULT_CHUNK_SIZE, ForkJoinPool.commonPool());
    }

    public ParallelReplacementStrategy(int chunkSize, ForkJoinPool forkJoinPool) {
        this.chunkSize = Math.max(MIN_CHUNK_SIZE, chunkSize);
        this.overlapSize = Math.min(MAX_OVERLAP_SIZE, this.chunkSize / 4);
        this.forkJoinPool = forkJoinPool;

        logger.debug("ParallelReplacementStrategy initialized with chunk size: {}KB, parallelism: {}",
                    this.chunkSize / 1024, forkJoinPool.getParallelism());
    }

    @Override
    public ProcessingResult replace(InputStream input,
                                  OutputStream output,
                                  Map<String, String> placeholders,
                                  Charset charset) {

        if (placeholders.isEmpty()) {
            return copyStreamDirect(input, output);
        }

        try {
            byte[] content = input.readAllBytes();
            byte[] processed = replace(content, placeholders, charset);

            output.write(processed);

            return new ProcessingResult(content.length,
                                      countReplacements(content, processed, placeholders, charset),
                                      placeholders.size());

        } catch (IOException e) {
            throw new PlaceholderProcessingException("Parallel replacement failed", e, "PARALLEL_IO_ERROR");
        }
    }

    @Override
    public byte[] replace(byte[] content,
                         Map<String, String> placeholders,
                         Charset charset) {

        if (placeholders.isEmpty()) {
            return content.clone();
        }

        String text = new String(content, charset);

        if (text.length() <= chunkSize) {
            return processSequential(text, placeholders).getBytes(charset);
        }

        try {
            String result = processParallel(text, placeholders);
            return result.getBytes(charset);

        } catch (Exception e) {
            logger.warn("Parallel processing failed, falling back to sequential: {}", e.getMessage());
            return processSequential(text, placeholders).getBytes(charset);
        }
    }

    private String processParallel(String content, Map<String, String> placeholders) {
        int parallelism = forkJoinPool.getParallelism();
        int effectiveChunkSize = Math.max(content.length() / parallelism, chunkSize);

        CompletableFuture<String>[] futures = createProcessingTasks(
            content, placeholders, effectiveChunkSize);

        CompletableFuture<Void> allTasks = CompletableFuture.allOf(futures);

        try {
            allTasks.get(30, TimeUnit.SECONDS);

            StringBuilder result = new StringBuilder(content.length());
            for (CompletableFuture<String> future : futures) {
                result.append(future.get());
            }

            return result.toString();

        } catch (Exception e) {
            throw new RuntimeException("Parallel processing failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<String>[] createProcessingTasks(String content,
                                                            Map<String, String> placeholders,
                                                            int effectiveChunkSize) {

        int numChunks = (content.length() + effectiveChunkSize - overlapSize - 1) / (effectiveChunkSize - overlapSize);
        CompletableFuture<String>[] futures = new CompletableFuture[numChunks];

        for (int i = 0; i < numChunks; i++) {
            final int start = i * (effectiveChunkSize - overlapSize);
            final int end = Math.min(start + effectiveChunkSize, content.length());
            final String chunk = content.substring(start, end);
            final boolean isLastChunk = i == numChunks - 1;

            futures[i] = CompletableFuture.supplyAsync(() -> {
                String processedChunk = processChunk(chunk, placeholders);

                if (!isLastChunk && processedChunk.length() > overlapSize) {
                    processedChunk = processedChunk.substring(0, processedChunk.length() - overlapSize);
                }

                return processedChunk;
            }, forkJoinPool);
        }

        return futures;
    }

    private String processChunk(String chunk, Map<String, String> placeholders) {
        String result = chunk;

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

    private String processSequential(String content, Map<String, String> placeholders) {
        String result = content;

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String placeholder = entry.getKey();
            String value = entry.getValue();

            if (value != null && result.contains(placeholder)) {
                result = result.replace(placeholder, value);
            }
        }

        return result;
    }

    private long countReplacements(byte[] original, byte[] processed,
                                 Map<String, String> placeholders, Charset charset) {

        String originalText = new String(original, charset);
        String processedText = new String(processed, charset);

        long count = 0;
        for (String placeholder : placeholders.keySet()) {
            int originalCount = countOccurrences(originalText, placeholder);
            int processedCount = countOccurrences(processedText, placeholder);
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
        try {
            long totalBytes = input.transferTo(output);
            return new ProcessingResult(totalBytes, 0, 0);
        } catch (IOException e) {
            throw new PlaceholderProcessingException("Stream copy failed", e, "STREAM_COPY_ERROR");
        }
    }

    @Override
    public String getStrategyName() {
        return "ParallelReplacementStrategy";
    }

    @Override
    public boolean supportsStreaming() {
        return false;
    }

    @Override
    public boolean supportsParallelProcessing() {
        return true;
    }

    @Override
    public long getRecommendedMinimumSize() {
        return chunkSize * 2;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getParallelism() {
        return forkJoinPool.getParallelism();
    }
}
