package net.kyver.placy.service.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

@Component
public class StreamProcessor {
    private static final Logger logger = LoggerFactory.getLogger(StreamProcessor.class);

    private static final int STREAM_BUFFER_SIZE = 64 * 1024;
    private static final int PARALLEL_THRESHOLD = 5 * 1024 * 1024;
    private static final int MIN_CHUNK_SIZE = 16 * 1024;

    public byte[] transformTextStream(byte[] textBytes, Map<String, String> placeholders) {
        if (placeholders.isEmpty()) {
            return textBytes;
        }

        if (textBytes.length > PARALLEL_THRESHOLD && placeholders.size() > 1) {
            return transformTextParallel(textBytes, placeholders);
        }

        return transformTextStandard(textBytes, placeholders);
    }

    private byte[] transformTextStandard(byte[] textBytes, Map<String, String> placeholders) {
        final String[] content = {new String(textBytes, StandardCharsets.UTF_8)};

        placeholders.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getKey().length(), e1.getKey().length()))
                .forEach(entry -> {
                    if (content[0].contains(entry.getKey())) {
                        content[0] = content[0].replace(entry.getKey(), entry.getValue());
                    }
                });

        return content[0].getBytes(StandardCharsets.UTF_8);
    }

    private byte[] transformTextParallel(byte[] textBytes, Map<String, String> placeholders) {
        try {
            String content = new String(textBytes, StandardCharsets.UTF_8);
            int parallelism = ForkJoinPool.getCommonPoolParallelism();
            int chunkSize = Math.max(content.length() / parallelism, MIN_CHUNK_SIZE);

            if (content.length() <= chunkSize) {
                return transformTextStandard(textBytes, placeholders);
            }

            int overlapSize = placeholders.keySet().stream()
                    .mapToInt(String::length)
                    .max()
                    .orElse(100);

            CompletableFuture<String>[] futures = new CompletableFuture[
                    (content.length() + chunkSize - overlapSize - 1) / (chunkSize - overlapSize)];

            for (int i = 0; i < futures.length; i++) {
                final int start = i * (chunkSize - overlapSize);
                final int end = Math.min(start + chunkSize, content.length());
                final String chunk = content.substring(start, end);
                final boolean isLastChunk = i == futures.length - 1;

                futures[i] = CompletableFuture.supplyAsync(() -> {
                    String processedChunk = chunk;
                    for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                        if (processedChunk.contains(entry.getKey())) {
                            processedChunk = processedChunk.replace(entry.getKey(), entry.getValue());
                        }
                    }

                    if (!isLastChunk && processedChunk.length() > overlapSize) {
                        processedChunk = processedChunk.substring(0, processedChunk.length() - overlapSize);
                    }
                    return processedChunk;
                }, ForkJoinPool.commonPool());
            }

            StringBuilder result = new StringBuilder(content.length());
            for (CompletableFuture<String> future : futures) {
                result.append(future.join());
            }

            return result.toString().getBytes(StandardCharsets.UTF_8);

        } catch (Exception e) {
            logger.warn("Parallel processing failed, falling back to standard approach: {}", e.getMessage());
            return transformTextStandard(textBytes, placeholders);
        }
    }
}