package net.kyver.placy.service;

import net.kyver.placy.config.EnvironmentSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class AsyncFileTransformService {
    private static final Logger logger = LoggerFactory.getLogger(AsyncFileTransformService.class);

    private final FileTransformService fileTransformService;
    private final EnvironmentSetup environmentSetup;
    private final Executor fileProcessingExecutor;

    @Autowired
    public AsyncFileTransformService(FileTransformService fileTransformService,
                                   EnvironmentSetup environmentSetup,
                                   @Qualifier("fileProcessingExecutor") Executor fileProcessingExecutor) {
        this.fileTransformService = fileTransformService;
        this.environmentSetup = environmentSetup;
        this.fileProcessingExecutor = fileProcessingExecutor;
    }

    public CompletableFuture<FileTransformResult> transformFileAsync(MultipartFile file,
                                                                    Map<String, String> placeholders) {
        if (environmentSetup.isAsyncProcessingEnabled()) {
            return processFileAsynchronously(file, placeholders);
        } else {
            return processFileSynchronously(file, placeholders);
        }
    }

    public CompletableFuture<List<FileTransformResult>> transformMultipleFiles(
            List<MultipartFile> files, Map<String, String> placeholders) {

        if (!environmentSetup.isAsyncProcessingEnabled()) {
            return CompletableFuture.supplyAsync(() -> {
                return files.stream()
                    .map(file -> {
                        try {
                            byte[] result = fileTransformService.transformFile(file, placeholders);
                            return new FileTransformResult(file.getOriginalFilename(), result, null);
                        } catch (Exception e) {
                            logger.error("Error processing file {}: {}", file.getOriginalFilename(), e.getMessage());
                            return new FileTransformResult(file.getOriginalFilename(), null, e);
                        }
                    })
                    .toList();
            }, fileProcessingExecutor);
        }

        List<CompletableFuture<FileTransformResult>> futures = files.stream()
            .map(file -> processFileAsynchronously(file, placeholders))
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .toList());
    }

    @Async("fileProcessingExecutor")
    public CompletableFuture<FileTransformResult> processFileAsynchronously(MultipartFile file,
                                                                           Map<String, String> placeholders) {
        String filename = file.getOriginalFilename();
        long startTime = System.currentTimeMillis();

        logger.debug("Starting async processing of file: {} (size: {} bytes)", filename, file.getSize());

        try {
            byte[] result = fileTransformService.transformFile(file, placeholders);
            long duration = System.currentTimeMillis() - startTime;

            logger.debug("Async processing completed for file: {} in {}ms (output size: {} bytes)",
                        filename, duration, result.length);

            return CompletableFuture.completedFuture(
                new FileTransformResult(filename, result, null));

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("Async processing failed for file: {} after {}ms: {}",
                        filename, duration, e.getMessage(), e);

            return CompletableFuture.completedFuture(
                new FileTransformResult(filename, null, e));
        }
    }

    private CompletableFuture<FileTransformResult> processFileSynchronously(MultipartFile file,
                                                                          Map<String, String> placeholders) {
        try {
            byte[] result = fileTransformService.transformFile(file, placeholders);
            return CompletableFuture.completedFuture(
                new FileTransformResult(file.getOriginalFilename(), result, null));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                new FileTransformResult(file.getOriginalFilename(), null, e));
        }
    }

    public static class FileTransformResult {
        private final String filename;
        private final byte[] data;
        private final Exception error;

        public FileTransformResult(String filename, byte[] data, Exception error) {
            this.filename = filename;
            this.data = data;
            this.error = error;
        }

        public String getFilename() {
            return filename;
        }

        public byte[] getData() {
            return data;
        }

        public Exception getError() {
            return error;
        }

        public boolean isSuccess() {
            return error == null && data != null;
        }

        public boolean hasError() {
            return error != null;
        }
    }
}
