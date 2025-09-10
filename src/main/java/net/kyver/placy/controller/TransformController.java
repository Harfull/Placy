package net.kyver.placy.controller;

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

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TransformController {
    private static final Logger logger = LoggerFactory.getLogger(TransformController.class);

    @Autowired
    private FileTransformService fileTransformService;

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

            byte[] transformedFile = fileTransformService.transformFile(file, placeholders);

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

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("File Transform Service is running");
    }
}
