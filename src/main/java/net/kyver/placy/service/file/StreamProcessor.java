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
    private static final int TEXT_PROCESSING_BUFFER_SIZE = 8 * 1024;
    private static final int PARALLEL_THRESHOLD = 10 * 1024 * 1024;

    public byte[] transformTextStream(byte[] textBytes, Map<String, String> placeholders) {
        if (placeholders.isEmpty()) {
            return textBytes;
        }

        if (textBytes.length > PARALLEL_THRESHOLD) {
            return transformTextParallel(textBytes, placeholders);
        }

        return transformTextStandard(textBytes, placeholders);
    }

    private byte[] transformTextStandard(byte[] textBytes, Map<String, String> placeholders) {
        String content = new String(textBytes, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            content = content.replace(entry.getKey(), entry.getValue());
        }

        return content.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] transformTextParallel(byte[] textBytes, Map<String, String> placeholders) {
        try {
            String content = new String(textBytes, StandardCharsets.UTF_8);
            int chunkSize = Math.max(content.length() / ForkJoinPool.getCommonPoolParallelism(), 10000);

            if (content.length() <= chunkSize) {
                return transformTextStandard(textBytes, placeholders);
            }

            CompletableFuture<String>[] futures = new CompletableFuture[
                (content.length() + chunkSize - 1) / chunkSize];

            for (int i = 0; i < futures.length; i++) {
                final int start = i * chunkSize;
                final int end = Math.min(start + chunkSize, content.length());
                final String chunk = content.substring(start, end);

                futures[i] = CompletableFuture.supplyAsync(() -> {
                    String processedChunk = chunk;
                    for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                        processedChunk = processedChunk.replace(entry.getKey(), entry.getValue());
                    }
                    return processedChunk;
                });
            }

            StringBuilder result = new StringBuilder(content.length());
            for (CompletableFuture<String> future : futures) {
                result.append(future.get());
            }

            return result.toString().getBytes(StandardCharsets.UTF_8);

        } catch (Exception e) {
            logger.warn("Parallel processing failed, falling back to standard approach", e);
            return transformTextStandard(textBytes, placeholders);
        }
    }

    public void copyStream(InputStream input, OutputStream output) throws IOException {
        try (ReadableByteChannel inputChannel = Channels.newChannel(input);
             WritableByteChannel outputChannel = Channels.newChannel(output)) {

            ByteBuffer buffer = ByteBuffer.allocateDirect(STREAM_BUFFER_SIZE);

            while (inputChannel.read(buffer) != -1) {
                buffer.flip();
                outputChannel.write(buffer);
                buffer.clear();
            }
        }
    }

    public byte[] readAllBytes(InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[STREAM_BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }

            return output.toByteArray();
        }
    }

    public void processStreamInChunks(InputStream input, ChunkProcessor processor) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        int bytesRead;

        while ((bytesRead = input.read(buffer)) != -1) {
            processor.processChunk(buffer, 0, bytesRead);
        }
    }

    @FunctionalInterface
    public interface ChunkProcessor {
        void processChunk(byte[] buffer, int offset, int length) throws IOException;
    }

    public int getOptimalBufferSize(long fileSize) {
        if (fileSize < 1024 * 1024) {
            return 8 * 1024;
        } else if (fileSize < 10 * 1024 * 1024) {
            return 32 * 1024;
        } else if (fileSize < 100 * 1024 * 1024) {
            return 64 * 1024;
        } else {
            return 128 * 1024;
        }
    }
}
