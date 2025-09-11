package net.kyver.placy.controller;

import net.kyver.placy.service.AsyncFileTransformService;
import net.kyver.placy.service.FileTransformService;
import net.kyver.placy.util.Logger;
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
    @Autowired
    private FileTransformService fileTransformService;

    @Autowired
    private AsyncFileTransformService asyncFileTransformService;

    @PostMapping(value = "/transform", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> transformFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("placeholders") String placeholdersJson) {

        try {
            Logger.info("Transform request: " + file.getOriginalFilename() + " (" + file.getSize() + " bytes)");

            if (file.isEmpty()) {
                Logger.warn("Empty file received");
                return ResponseEntity.badRequest().build();
            }

            if (!fileTransformService.isSupportedFileType(file.getOriginalFilename())) {
                Logger.warn("Unsupported file: " + file.getOriginalFilename());
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
            }

            Map<String, String> placeholders = fileTransformService.parsePlaceholders(placeholdersJson);

            CompletableFuture<AsyncFileTransformService.FileTransformResult> futureResult =
                asyncFileTransformService.transformFileAsync(file, placeholders);

            AsyncFileTransformService.FileTransformResult result = futureResult.join();

            if (result.hasError()) {
                Logger.error("Transform failed: " + result.getError().getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }

            byte[] transformedFile = result.getData();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDispositionFormData("attachment", file.getOriginalFilename());
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(transformedFile.length);

            Logger.success("Transform complete: " + file.getOriginalFilename() + " (" + transformedFile.length + " bytes)");

            return new ResponseEntity<>(transformedFile, headers, HttpStatus.OK);

        } catch (IllegalArgumentException e) {
            Logger.error("Invalid params: " + e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            Logger.error("Transform error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping(value = "/transform/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> transformMultipleFiles(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam("placeholders") String placeholdersJson) {

        try {
            Logger.info("Batch transform: " + files.length + " files");

            if (files.length == 0) {
                Logger.warn("No files in batch request");
                return ResponseEntity.badRequest().build();
            }

            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    Logger.warn("Empty file in batch: " + file.getOriginalFilename());
                    return ResponseEntity.badRequest().build();
                }
                if (!fileTransformService.isSupportedFileType(file.getOriginalFilename())) {
                    Logger.warn("Unsupported file in batch: " + file.getOriginalFilename());
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
                Logger.error("Batch errors: " + errors.size() + "/" + results.size() + " failed");
                errors.forEach(error ->
                    Logger.error("File error " + error.getFilename() + ": " + error.getError().getMessage()));
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }

            byte[] zipData = createZipArchive(results);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDispositionFormData("attachment", "transformed_files.zip");
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(zipData.length);

            long totalInputSize = Arrays.stream(files).mapToLong(MultipartFile::getSize).sum();
            Logger.success("Batch complete: " + results.size() + " files (" + totalInputSize + " → " + zipData.length + " bytes)");

            return new ResponseEntity<>(zipData, headers, HttpStatus.OK);

        } catch (IllegalArgumentException e) {
            Logger.error("Invalid batch params: " + e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            Logger.error("Batch transform error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private byte[] createZipArchive(List<AsyncFileTransformService.FileTransformResult> results) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (AsyncFileTransformService.FileTransformResult result : results) {
                if (!result.hasError()) {
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

        return ResponseEntity.ok("Placy Transform Service is running");
    }
}
