package net.kyver.placy.controller;

import net.kyver.placy.service.AsyncFileTransformService;
import net.kyver.placy.service.FileTransformService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
public class TransformController {
    private static final Logger logger = LoggerFactory.getLogger(TransformController.class);

    @Autowired
    private FileTransformService fileTransformService;

    @Autowired
    private AsyncFileTransformService asyncFileTransformService;

    @PostMapping(value = "/transform", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> transformFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("placeholders") String placeholdersJson) {

        try {
            logger.info("Received transform request for file: {} (size: {} bytes)",
                       file.getOriginalFilename(), file.getSize());

            if (file.isEmpty()) {
                logger.warn("Empty file received");
                return ResponseEntity.badRequest().build();
            }

            if (!fileTransformService.isSupportedFileType(file.getOriginalFilename())) {
                logger.warn("Unsupported file type: {}", file.getOriginalFilename());
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
            }

            Map<String, String> placeholders = fileTransformService.parsePlaceholders(placeholdersJson);

            CompletableFuture<AsyncFileTransformService.FileTransformResult> futureResult =
                asyncFileTransformService.transformFileAsync(file, placeholders);

            AsyncFileTransformService.FileTransformResult result = futureResult.join();

            if (result.hasError()) {
                logger.error("Error transforming file: {}", result.getError().getMessage(), result.getError());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }

            byte[] transformedFile = result.getData();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDispositionFormData("attachment", file.getOriginalFilename());
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(transformedFile.length);

            logger.info("Successfully transformed file: {} (output size: {} bytes)",
                       file.getOriginalFilename(), transformedFile.length);

            return new ResponseEntity<>(transformedFile, headers, HttpStatus.OK);

        } catch (IllegalArgumentException e) {
            logger.error("Invalid request parameters: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error transforming file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping(value = "/transform/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> transformMultipleFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("placeholders") String placeholdersJson) {

        try {
            logger.info("Received batch transform request for {} files", files.length);

            if (files.length == 0) {
                logger.warn("No files received in batch request");
                return ResponseEntity.badRequest().build();
            }

            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    logger.warn("Empty file in batch: {}", file.getOriginalFilename());
                    return ResponseEntity.badRequest().build();
                }
                if (!fileTransformService.isSupportedFileType(file.getOriginalFilename())) {
                    logger.warn("Unsupported file type in batch: {}", file.getOriginalFilename());
                    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
                }
            }

            Map<String, String> placeholders = fileTransformService.parsePlaceholders(placeholdersJson);
            List<MultipartFile> fileList = Arrays.asList(files);

            CompletableFuture<List<AsyncFileTransformService.FileTransformResult>> futureResults =
                asyncFileTransformService.transformMultipleFiles(fileList, placeholders);

            List<AsyncFileTransformService.FileTransformResult> results = futureResults.join();

            List<AsyncFileTransformService.FileTransformResult> errors = results.stream()
                .filter(AsyncFileTransformService.FileTransformResult::hasError)
                .toList();

            if (!errors.isEmpty()) {
                logger.error("Batch processing had {} errors out of {} files", errors.size(), results.size());
                errors.forEach(error ->
                    logger.error("Error in file {}: {}", error.getFilename(), error.getError().getMessage()));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }

            byte[] zipData = createZipArchive(results);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDispositionFormData("attachment", "transformed_files.zip");
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(zipData.length);

            long totalInputSize = Arrays.stream(files).mapToLong(MultipartFile::getSize).sum();
            logger.info("Successfully transformed {} files (total input: {} bytes, output: {} bytes)",
                       results.size(), totalInputSize, zipData.length);

            return new ResponseEntity<>(zipData, headers, HttpStatus.OK);

        } catch (IllegalArgumentException e) {
            logger.error("Invalid batch request parameters: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error in batch file transformation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private byte[] createZipArchive(List<AsyncFileTransformService.FileTransformResult> results) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (AsyncFileTransformService.FileTransformResult result : results) {
                if (result.isSuccess()) {
                    ZipEntry entry = new ZipEntry(result.getFilename());
                    entry.setSize(result.getData().length);
                    zos.putNextEntry(entry);
                    zos.write(result.getData());
                    zos.closeEntry();
                }
            }

            zos.finish();
            return baos.toByteArray();
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("File Transform Service is running");
    }
}
