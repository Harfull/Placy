package net.kyver.placy.service.archive;

import net.kyver.placy.service.file.FileTypeDetector;
import net.kyver.placy.service.file.StreamProcessor;
import net.kyver.placy.service.image.ImageProcessor;
import net.kyver.placy.service.document.DocumentProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.util.jar.*;

@Component
public class ArchiveProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ArchiveProcessor.class);

    private final FileTypeDetector fileTypeDetector;
    private final StreamProcessor streamProcessor;
    private final ClassProcessor classProcessor;
    private final ImageProcessor imageProcessor;
    private final DocumentProcessor documentProcessor;

    @Autowired
    public ArchiveProcessor(FileTypeDetector fileTypeDetector,
                            StreamProcessor streamProcessor,
                            ClassProcessor classProcessor,
                            ImageProcessor imageProcessor,
                            DocumentProcessor documentProcessor) {
        this.fileTypeDetector = fileTypeDetector;
        this.streamProcessor = streamProcessor;
        this.classProcessor = classProcessor;
        this.imageProcessor = imageProcessor;
        this.documentProcessor = documentProcessor;
    }

    public byte[] processArchive(byte[] archiveBytes, String filename, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return archiveBytes;
        }

        try {
            String extension = getFileExtension(filename).toLowerCase();

            return switch (extension) {
                case ".jar", ".war", ".ear", ".aar" -> processJarArchive(archiveBytes, placeholders);
                case ".zip", ".apk", ".xpi", ".crx", ".vsix", ".nupkg", ".snupkg" ->
                        processZipArchive(archiveBytes, placeholders);
                default -> {
                    logger.debug("Unsupported archive format: {}", extension);
                    yield archiveBytes;
                }
            };
        } catch (Exception e) {
            logger.error("Failed to process archive {}: {}", filename, e.getMessage(), e);
            return archiveBytes;
        }
    }

    private byte[] processJarArchive(byte[] jarBytes, Map<String, String> placeholders) throws IOException {
        logger.debug("Processing JAR archive of {} bytes", jarBytes.length);

        try (ByteArrayInputStream input = new ByteArrayInputStream(jarBytes);
             JarInputStream jarIn = new JarInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Manifest manifest = jarIn.getManifest();

            try (JarOutputStream jarOut = manifest != null ?
                    new JarOutputStream(output, manifest) :
                    new JarOutputStream(output)) {

                JarEntry entry;
                while ((entry = jarIn.getNextJarEntry()) != null) {
                    processJarEntry(jarIn, jarOut, entry, placeholders);
                }

                jarOut.finish();
                byte[] result = output.toByteArray();
                logger.debug("JAR processing complete. Input: {} bytes, Output: {} bytes",
                        jarBytes.length, result.length);
                return result;
            }
        }
    }

    private byte[] processZipArchive(byte[] zipBytes, Map<String, String> placeholders) throws IOException {
        logger.debug("Processing ZIP archive of {} bytes", zipBytes.length);

        try (ByteArrayInputStream input = new ByteArrayInputStream(zipBytes);
             ZipInputStream zipIn = new ZipInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            try (ZipOutputStream zipOut = new ZipOutputStream(output)) {
                ZipEntry entry;
                while ((entry = zipIn.getNextEntry()) != null) {
                    processZipEntry(zipIn, zipOut, entry, placeholders);
                }

                zipOut.finish();
                byte[] result = output.toByteArray();
                logger.debug("ZIP processing complete. Input: {} bytes, Output: {} bytes",
                        zipBytes.length, result.length);
                return result;
            }
        }
    }

    private void processJarEntry(JarInputStream jarIn, JarOutputStream jarOut,
                                 JarEntry originalEntry, Map<String, String> placeholders) throws IOException {

        if (originalEntry.isDirectory()) {
            JarEntry newEntry = new JarEntry(originalEntry.getName());
            copyJarEntryMetadata(originalEntry, newEntry);
            jarOut.putNextEntry(newEntry);
            jarOut.closeEntry();
            return;
        }

        byte[] entryData = readEntryData(jarIn);

        byte[] processedData = processEntryContent(originalEntry.getName(), entryData, placeholders);

        JarEntry newEntry = new JarEntry(originalEntry.getName());
        copyJarEntryMetadata(originalEntry, newEntry);

        newEntry.setSize(processedData.length);
        if (newEntry.getMethod() == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(processedData);
            newEntry.setCrc(crc.getValue());
        }

        jarOut.putNextEntry(newEntry);
        jarOut.write(processedData);
        jarOut.closeEntry();
    }

    private void processZipEntry(ZipInputStream zipIn, ZipOutputStream zipOut,
                                 ZipEntry originalEntry, Map<String, String> placeholders) throws IOException {

        if (originalEntry.isDirectory()) {
            ZipEntry newEntry = new ZipEntry(originalEntry.getName());
            copyZipEntryMetadata(originalEntry, newEntry);
            zipOut.putNextEntry(newEntry);
            zipOut.closeEntry();
            return;
        }

        byte[] entryData = readEntryData(zipIn);

        byte[] processedData = processEntryContent(originalEntry.getName(), entryData, placeholders);

        ZipEntry newEntry = new ZipEntry(originalEntry.getName());
        copyZipEntryMetadata(originalEntry, newEntry);

        zipOut.putNextEntry(newEntry);
        zipOut.write(processedData);
        zipOut.closeEntry();
    }

    private byte[] processEntryContent(String entryName, byte[] originalData, Map<String, String> placeholders) {
        try {
            if (isArchiveFile(entryName)) {
                logger.debug("Processing nested archive: {}", entryName);
                return processArchive(originalData, entryName, placeholders);
            }

            if (entryName.endsWith(".class")) {
                return classProcessor.replacePlaceholdersInClass(originalData, placeholders);
            } else if (isTextFile(entryName, originalData)) {
                return streamProcessor.transformTextStream(originalData, placeholders);
            } else if (isImageFile(entryName)) {
                return imageProcessor.processImage(originalData, entryName, placeholders);
            } else if (documentProcessor.isSupportedDocument(entryName)) {
                return documentProcessor.processDocument(originalData, entryName, placeholders);
            }

            return originalData;

        } catch (Exception e) {
            logger.warn("Failed to process entry {}: {}", entryName, e.getMessage());
            return originalData;
        }
    }

    private byte[] readEntryData(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int bytesRead;

        while ((bytesRead = inputStream.read(data)) != -1) {
            buffer.write(data, 0, bytesRead);
        }

        return buffer.toByteArray();
    }

    private void copyJarEntryMetadata(JarEntry source, JarEntry target) throws IOException {
        target.setTime(source.getTime());
        target.setMethod(source.getMethod());

        if (source.getComment() != null) {
            target.setComment(source.getComment());
        }
        if (source.getExtra() != null) {
            target.setExtra(source.getExtra());
        }

        if (source.getAttributes() != null) {
            target.getAttributes().putAll(source.getAttributes());
        }
    }

    private void copyZipEntryMetadata(ZipEntry source, ZipEntry target) {
        target.setTime(source.getTime());
        target.setMethod(source.getMethod());

        if (source.getComment() != null) {
            target.setComment(source.getComment());
        }
        if (source.getExtra() != null) {
            target.setExtra(source.getExtra());
        }
    }

    private boolean isArchiveFile(String filename) {
        String extension = getFileExtension(filename).toLowerCase();
        return Set.of(".jar", ".war", ".ear", ".aar", ".zip", ".apk", ".xpi",
                ".crx", ".vsix", ".nupkg", ".snupkg").contains(extension);
    }

    private boolean isTextFile(String filename, byte[] data) {
        return fileTypeDetector.isTextFile(filename, data);
    }

    private boolean isImageFile(String filename) {
        String extension = getFileExtension(filename).toLowerCase();
        return fileTypeDetector.getImageExtensions().contains(extension);
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }

        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return "";
        }

        return filename.substring(lastDot);
    }
}