package net.kyver.placy.controller;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.kyver.placy.util.PlaceholderTransformService;
import net.kyver.placy.util.TransformationResult;
import net.kyver.placy.core.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/v1")
public class TransformController {
    private static final Logger logger = LoggerFactory.getLogger(TransformController.class);

    private final PlaceholderTransformService transformService;
    private final Gson gson;

    @Autowired
    public TransformController(PlaceholderTransformService transformService) {
        this.transformService = transformService;
        this.gson = new Gson();
    }

    @PostMapping(value = "/transform", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transformFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("placeholders") String placeholdersJson) {

        String filename = file.getOriginalFilename();
        logger.info("Transform request: {} ({} bytes)", filename, file.getSize());

        try {
            if (file.isEmpty()) {
                logger.warn("Empty file received: {}", filename);
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "File is empty", "filename", filename));
            }

            Map<String, String> placeholders = parsePlaceholders(placeholdersJson);

            if (!transformService.isFileSupported(filename, file.getContentType())) {
                logger.warn("Unsupported file type: {} (MIME: {})", filename, file.getContentType());
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Map.of("error", "Unsupported file type",
                               "filename", filename,
                               "supportedExtensions", transformService.getSupportedExtensions()));
            }

            ValidationResult validation = transformService.validateTransformation(
                filename, file.getContentType(), placeholders);

            if (!validation.isValid()) {
                logger.warn("Validation failed for {}: {}", filename, validation.getErrors());
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Validation failed",
                               "details", validation.getErrors(),
                               "warnings", validation.getWarnings()));
            }

            TransformationResult result = transformService.transformFile(file, placeholders);

            if (!result.isSuccess()) {
                logger.error("Transform failed for {}: {}", filename, result.getErrorMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", result.getErrorMessage(), "filename", filename));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(result.getContent().length);

            if (result.getProcessingResult() != null) {
                headers.add("X-Processing-Time-Ms",
                    String.valueOf(result.getProcessingResult().getProcessingTimeMillis()));
                headers.add("X-Throughput-MBps",
                    String.valueOf(result.getProcessingResult().getThroughputMBps()));
                headers.add("X-Replacements-Made",
                    String.valueOf(result.getProcessingResult().getReplacementCount()));
            }

            logger.info("Transform completed successfully: {} ({} bytes processed)",
                       filename, result.getContent().length);

            return new ResponseEntity<>(result.getContent(), headers, HttpStatus.OK);

        } catch (Exception e) {
            logger.error("Transform error for {}: {}", filename, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error",
                           "message", e.getMessage(),
                           "filename", filename));
        }
    }

    @PostMapping(value = "/transform/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transformMultipleFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("placeholders") String placeholdersJson) {

        logger.info("Batch transform request: {} files", files.length);

        try {
            if (files.length == 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "No files provided"));
            }

            Map<String, String> placeholders = parsePlaceholders(placeholdersJson);

            List<String> unsupportedFiles = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    return ResponseEntity.badRequest()
                        .body(Map.of("error", "Empty file in batch", "filename", file.getOriginalFilename()));
                }

                if (!transformService.isFileSupported(file.getOriginalFilename(), file.getContentType())) {
                    unsupportedFiles.add(file.getOriginalFilename());
                }
            }

            if (!unsupportedFiles.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Map.of("error", "Unsupported file types",
                               "unsupportedFiles", unsupportedFiles,
                               "supportedExtensions", transformService.getSupportedExtensions()));
            }

            List<CompletableFuture<TransformationResult>> futures = new ArrayList<>();
            for (MultipartFile file : files) {
                futures.add(transformService.transformFileAsync(file, placeholders));
            }

            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
            allFutures.join();

            List<TransformationResult> results = new ArrayList<>();
            for (CompletableFuture<TransformationResult> future : futures) {
                results.add(future.get());
            }

            byte[] zipContent = createZipArchive(results);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDispositionFormData("attachment", "transformed_files.zip");
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(zipContent.length);

            long totalReplacements = results.stream()
                .filter(TransformationResult::isSuccess)
                .mapToLong(r -> r.getProcessingResult() != null ? r.getProcessingResult().getReplacementCount() : 0)
                .sum();

            headers.add("X-Files-Processed", String.valueOf(results.size()));
            headers.add("X-Total-Replacements", String.valueOf(totalReplacements));

            logger.info("Batch transform completed: {} files processed, {} total replacements",
                       results.size(), totalReplacements);

            return new ResponseEntity<>(zipContent, headers, HttpStatus.OK);

        } catch (Exception e) {
            logger.error("Batch transform error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Batch processing failed", "message", e.getMessage()));
        }
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        try {
            Map<String, Object> metrics = transformService.getPerformanceMetrics();
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            logger.error("Failed to get metrics: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to retrieve metrics"));
        }
    }

    @GetMapping("/supported-types")
    public ResponseEntity<Map<String, Object>> getSupportedTypes() {
        return ResponseEntity.ok(Map.of(
            "extensions", transformService.getSupportedExtensions(),
            "mimeTypes", transformService.getSupportedMimeTypes()
        ));
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validatePlaceholders(
            @RequestParam("filename") String filename,
            @RequestParam(value = "mimeType", required = false) String mimeType,
            @RequestParam("placeholders") String placeholdersJson) {

        try {
            Map<String, String> placeholders = parsePlaceholders(placeholdersJson);
            ValidationResult result = transformService.validateTransformation(filename, mimeType, placeholders);

            return ResponseEntity.ok(Map.of(
                "valid", result.isValid(),
                "errors", result.getErrors(),
                "warnings", result.getWarnings(),
                "supported", transformService.isFileSupported(filename, mimeType)
            ));

        } catch (Exception e) {
            logger.error("Validation error: {}", e.getMessage(), e);
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Validation failed", "message", e.getMessage()));
        }
    }

    private Map<String, String> parsePlaceholders(String placeholdersJson) {
        try {
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> placeholders = gson.fromJson(placeholdersJson, type);
            return placeholders != null ? placeholders : new HashMap<>();
        } catch (Exception e) {
            logger.warn("Failed to parse placeholders JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private byte[] createZipArchive(List<TransformationResult> results) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (TransformationResult result : results) {
                if (result.isSuccess()) {
                    ZipEntry entry = new ZipEntry(result.getFilename());
                    zos.putNextEntry(entry);
                    zos.write(result.getContent());
                    zos.closeEntry();
                } else {
                    String errorFilename = result.getFilename() + ".error.txt";
                    ZipEntry errorEntry = new ZipEntry(errorFilename);
                    zos.putNextEntry(errorEntry);
                    zos.write(("Error processing " + result.getFilename() + ": " + result.getErrorMessage()).getBytes());
                    zos.closeEntry();
                }
            }

            zos.finish();
            return baos.toByteArray();
        }
    }
}
