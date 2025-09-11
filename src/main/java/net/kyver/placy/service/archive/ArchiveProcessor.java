package net.kyver.placy.service.archive;

import net.kyver.placy.config.EnvironmentSetup;
import net.kyver.placy.service.file.FileTypeDetector;
import net.kyver.placy.service.file.StreamProcessor;
import net.kyver.placy.service.image.ImageProcessor;
import net.kyver.placy.service.document.DocumentProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
    private final Executor fileProcessingExecutor;

    @Autowired
    public ArchiveProcessor(FileTypeDetector fileTypeDetector,
                            StreamProcessor streamProcessor,
                            ClassProcessor classProcessor,
                            ImageProcessor imageProcessor,
                            DocumentProcessor documentProcessor,
                            @Qualifier("fileProcessingExecutor") Executor fileProcessingExecutor) {
        this.fileTypeDetector = fileTypeDetector;
        this.streamProcessor = streamProcessor;
        this.classProcessor = classProcessor;
        this.imageProcessor = imageProcessor;
        this.documentProcessor = documentProcessor;
        this.fileProcessingExecutor = fileProcessingExecutor;
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
        logger.debug("Processing JAR archive of {} bytes with async={}", jarBytes.length, EnvironmentSetup.isAsyncProcessingEnabled());

        try (ByteArrayInputStream input = new ByteArrayInputStream(jarBytes);
             JarInputStream jarIn = new JarInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            Manifest manifest = jarIn.getManifest();

            try (JarOutputStream jarOut = manifest != null ?
                    new JarOutputStream(output, manifest) :
                    new JarOutputStream(output)) {

                if (EnvironmentSetup.isAsyncProcessingEnabled()) {
                    processJarEntriesAsync(jarIn, jarOut, placeholders);
                } else {
                    processJarEntriesSync(jarIn, jarOut, placeholders);
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
        logger.debug("Processing ZIP archive of {} bytes with async={}", zipBytes.length, EnvironmentSetup.isAsyncProcessingEnabled());

        try (ByteArrayInputStream input = new ByteArrayInputStream(zipBytes);
             ZipInputStream zipIn = new ZipInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            try (ZipOutputStream zipOut = new ZipOutputStream(output)) {
                if (EnvironmentSetup.isAsyncProcessingEnabled()) {
                    processZipEntriesAsync(zipIn, zipOut, placeholders);
                } else {
                    processZipEntriesSync(zipIn, zipOut, placeholders);
                }

                zipOut.finish();
                byte[] result = output.toByteArray();
                logger.debug("ZIP processing complete. Input: {} bytes, Output: {} bytes",
                        zipBytes.length, result.length);
                return result;
            }
        }
    }

    private void processJarEntriesAsync(JarInputStream jarIn, JarOutputStream jarOut, Map<String, String> placeholders) throws IOException {
        List<JarEntryData> entries = new ArrayList<>();

        JarEntry entry;
        while ((entry = jarIn.getNextJarEntry()) != null) {
            byte[] data = entry.isDirectory() ? new byte[0] : readEntryData(jarIn);
            entries.add(new JarEntryData(entry, data));
        }

        if (entries.isEmpty()) {
            return;
        }

        List<JarEntryData> filesToProcess = entries.stream()
                .filter(e -> !e.entry.isDirectory())
                .toList();

        Map<String, byte[]> processedData = processBatchedEntries(filesToProcess, placeholders);

        for (JarEntryData entryData : entries) {
            JarEntry originalEntry = entryData.entry;

            if (originalEntry.isDirectory()) {
                JarEntry newEntry = new JarEntry(originalEntry.getName());
                copyJarEntryMetadata(originalEntry, newEntry);
                jarOut.putNextEntry(newEntry);
                jarOut.closeEntry();
            } else {
                String entryName = originalEntry.getName();
                byte[] processedBytes = processedData.getOrDefault(entryName, entryData.data);

                JarEntry newEntry = new JarEntry(originalEntry.getName());
                copyJarEntryMetadata(originalEntry, newEntry);
                newEntry.setSize(processedBytes.length);

                if (newEntry.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(processedBytes);
                    newEntry.setCrc(crc.getValue());
                }

                jarOut.putNextEntry(newEntry);
                jarOut.write(processedBytes);
                jarOut.closeEntry();
            }
        }

        logger.debug("Processed {} JAR entries asynchronously", entries.size());
    }

    private void processZipEntriesAsync(ZipInputStream zipIn, ZipOutputStream zipOut, Map<String, String> placeholders) throws IOException {
        List<ZipEntryData> entries = new ArrayList<>();

        ZipEntry entry;
        while ((entry = zipIn.getNextEntry()) != null) {
            byte[] data = entry.isDirectory() ? new byte[0] : readEntryData(zipIn);
            entries.add(new ZipEntryData(entry, data));
        }

        if (entries.isEmpty()) {
            return;
        }

        List<ZipEntryData> filesToProcess = entries.stream()
                .filter(e -> !e.entry.isDirectory())
                .toList();

        Map<String, byte[]> processedData = processBatchedZipEntries(filesToProcess, placeholders);

        for (ZipEntryData entryData : entries) {
            ZipEntry originalEntry = entryData.entry;

            if (originalEntry.isDirectory()) {
                ZipEntry newEntry = new ZipEntry(originalEntry.getName());
                copyZipEntryMetadata(originalEntry, newEntry);
                zipOut.putNextEntry(newEntry);
                zipOut.closeEntry();
            } else {
                String entryName = originalEntry.getName();
                byte[] originalData = entryData.data;
                byte[] processedBytes = processedData.getOrDefault(entryName, originalData);

                ZipEntry newEntry = new ZipEntry(originalEntry.getName());

                newEntry.setTime(originalEntry.getTime());
                if (originalEntry.getComment() != null) {
                    newEntry.setComment(originalEntry.getComment());
                }
                if (originalEntry.getExtra() != null) {
                    newEntry.setExtra(originalEntry.getExtra());
                }

                if (originalEntry.getMethod() == ZipEntry.STORED) {
                    if (processedBytes.length != originalData.length) {
                        newEntry.setMethod(ZipEntry.DEFLATED);
                    } else {
                        newEntry.setMethod(ZipEntry.STORED);
                        CRC32 crc = new CRC32();
                        crc.update(processedBytes);
                        newEntry.setCrc(crc.getValue());
                        newEntry.setSize(processedBytes.length);
                        newEntry.setCompressedSize(processedBytes.length);
                    }
                } else {
                    newEntry.setMethod(originalEntry.getMethod());
                }

                zipOut.putNextEntry(newEntry);
                zipOut.write(processedBytes);
                zipOut.closeEntry();
            }
        }

        logger.debug("Processed {} ZIP entries asynchronously", entries.size());
    }

    private Map<String, byte[]> processBatchedEntries(List<JarEntryData> entries, Map<String, String> placeholders) {
        Map<String, byte[]> results = new ConcurrentHashMap<>();

        int availableThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
        int batchSize = Math.max(10, entries.size() / availableThreads);

        List<List<JarEntryData>> batches = partitionList(entries, batchSize);
        List<CompletableFuture<Void>> batchFutures = new ArrayList<>();

        logger.debug("Processing {} entries in {} batches with {} threads",
                    entries.size(), batches.size(), availableThreads);

        for (List<JarEntryData> batch : batches) {
            CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                for (JarEntryData entryData : batch) {
                    try {
                        String entryName = entryData.entry.getName();
                        byte[] processedData = processEntryContent(entryName, entryData.data, placeholders);
                        results.put(entryName, processedData);
                    } catch (Exception e) {
                        logger.warn("Failed to process entry {}: {}", entryData.entry.getName(), e.getMessage());
                        results.put(entryData.entry.getName(), entryData.data);
                    }
                }
            }, fileProcessingExecutor);
            batchFutures.add(batchFuture);
        }

        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();

        logger.debug("Completed processing {} entries across {} batch futures", entries.size(), batches.size());
        return results;
    }

    private Map<String, byte[]> processBatchedZipEntries(List<ZipEntryData> entries, Map<String, String> placeholders) {
        Map<String, byte[]> results = new ConcurrentHashMap<>();

        int availableThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
        int batchSize = Math.max(10, entries.size() / availableThreads);

        List<List<ZipEntryData>> batches = partitionList(entries, batchSize);
        List<CompletableFuture<Void>> batchFutures = new ArrayList<>();

        logger.debug("Processing {} entries in {} batches with {} threads",
                    entries.size(), batches.size(), availableThreads);

        for (List<ZipEntryData> batch : batches) {
            CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                for (ZipEntryData entryData : batch) {
                    try {
                        String entryName = entryData.entry.getName();
                        byte[] processedData = processEntryContent(entryName, entryData.data, placeholders);
                        results.put(entryName, processedData);
                    } catch (Exception e) {
                        logger.warn("Failed to process entry {}: {}", entryData.entry.getName(), e.getMessage());
                        results.put(entryData.entry.getName(), entryData.data);
                    }
                }
            }, fileProcessingExecutor);
            batchFutures.add(batchFuture);
        }

        CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();

        logger.debug("Completed processing {} entries across {} batch futures", entries.size(), batches.size());
        return results;
    }

    private <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }

    private void processJarEntriesSync(JarInputStream jarIn, JarOutputStream jarOut, Map<String, String> placeholders) throws IOException {
        JarEntry entry;
        int entryCount = 0;
        while ((entry = jarIn.getNextJarEntry()) != null) {
            processJarEntry(jarIn, jarOut, entry, placeholders);
            entryCount++;
        }
        logger.debug("Processed {} JAR entries synchronously", entryCount);
    }

    private void processZipEntriesSync(ZipInputStream zipIn, ZipOutputStream zipOut, Map<String, String> placeholders) throws IOException {
        ZipEntry entry;
        int entryCount = 0;
        while ((entry = zipIn.getNextEntry()) != null) {
            processZipEntry(zipIn, zipOut, entry, placeholders);
            entryCount++;
        }
        logger.debug("Processed {} ZIP entries synchronously", entryCount);
    }

    private void processJarEntry(JarInputStream jarIn, JarOutputStream jarOut,
                                 JarEntry originalEntry, Map<String, String> placeholders) throws IOException {

        if (originalEntry.isDirectory()) {
            JarEntry newEntry = new JarEntry(originalEntry.getName());
            newEntry.setTime(originalEntry.getTime());
            if (originalEntry.getComment() != null) {
                newEntry.setComment(originalEntry.getComment());
            }
            if (originalEntry.getExtra() != null) {
                newEntry.setExtra(originalEntry.getExtra());
            }
            if (originalEntry.getAttributes() != null) {
                newEntry.getAttributes().putAll(originalEntry.getAttributes());
            }
            jarOut.putNextEntry(newEntry);
            jarOut.closeEntry();
            return;
        }

        byte[] entryData = readEntryData(jarIn);
        byte[] processedData = processEntryContent(originalEntry.getName(), entryData, placeholders);

        JarEntry newEntry = new JarEntry(originalEntry.getName());

        newEntry.setTime(originalEntry.getTime());
        if (originalEntry.getComment() != null) {
            newEntry.setComment(originalEntry.getComment());
        }
        if (originalEntry.getExtra() != null) {
            newEntry.setExtra(originalEntry.getExtra());
        }
        if (originalEntry.getAttributes() != null) {
            newEntry.getAttributes().putAll(originalEntry.getAttributes());
        }

        newEntry.setMethod(ZipEntry.DEFLATED);

        try {
            jarOut.putNextEntry(newEntry);
            jarOut.write(processedData);
            jarOut.closeEntry();
        } catch (ZipException e) {
            logger.warn("Failed to write JAR entry '{}', trying with minimal entry: {}",
                    originalEntry.getName(), e.getMessage());

            JarEntry fallbackEntry = new JarEntry(originalEntry.getName());
            fallbackEntry.setTime(System.currentTimeMillis());
            fallbackEntry.setMethod(ZipEntry.DEFLATED);
            if (originalEntry.getAttributes() != null) {
                fallbackEntry.getAttributes().putAll(originalEntry.getAttributes());
            }

            try {
                jarOut.putNextEntry(fallbackEntry);
                jarOut.write(processedData);
                jarOut.closeEntry();
            } catch (ZipException e2) {
                logger.error("Failed to write JAR entry '{}' even with minimal entry, skipping: {}",
                        originalEntry.getName(), e2.getMessage());
            }
        }
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

        newEntry.setTime(originalEntry.getTime());
        if (originalEntry.getComment() != null) {
            newEntry.setComment(originalEntry.getComment());
        }
        if (originalEntry.getExtra() != null) {
            newEntry.setExtra(originalEntry.getExtra());
        }

        newEntry.setMethod(ZipEntry.DEFLATED);

        try {
            zipOut.putNextEntry(newEntry);
            zipOut.write(processedData);
            zipOut.closeEntry();
        } catch (ZipException e) {
            logger.warn("Failed to write ZIP entry '{}' with original method, trying with minimal entry: {}",
                    originalEntry.getName(), e.getMessage());

            ZipEntry fallbackEntry = new ZipEntry(originalEntry.getName());
            fallbackEntry.setTime(System.currentTimeMillis());
            fallbackEntry.setMethod(ZipEntry.DEFLATED);

            try {
                zipOut.putNextEntry(fallbackEntry);
                zipOut.write(processedData);
                zipOut.closeEntry();
            } catch (ZipException e2) {
                logger.error("Failed to write ZIP entry '{}' even with minimal entry, skipping: {}",
                        originalEntry.getName(), e2.getMessage());
            }
        }
    }

    private byte[] processEntryContent(String entryName, byte[] originalData, Map<String, String> placeholders) {
        try {
            if (originalData == null || originalData.length == 0) {
                logger.warn("Entry {} has no data, skipping processing", entryName);
                return originalData;
            }

            if (isArchiveFile(entryName)) {
                logger.debug("Processing nested archive: {} ({} bytes)", entryName, originalData.length);
                byte[] processed = processArchive(originalData, entryName, placeholders);
                logger.debug("Nested archive {} processed: {} -> {} bytes", entryName, originalData.length, processed.length);
                return processed;
            }

            if (entryName.endsWith(".class")) {
                logger.debug("Processing class file: {} ({} bytes)", entryName, originalData.length);
                byte[] processed = classProcessor.replacePlaceholdersInClass(originalData, placeholders);
                if (processed.length == 0) {
                    logger.warn("Class processing returned empty data for {}, using original", entryName);
                    return originalData;
                }
                return processed;
            } else if (isTextFile(entryName, originalData)) {
                logger.debug("Processing text file: {} ({} bytes)", entryName, originalData.length);
                return streamProcessor.transformTextStream(originalData, placeholders);
            } else if (isImageFile(entryName)) {
                logger.debug("Processing image file: {} ({} bytes)", entryName, originalData.length);
                return imageProcessor.processImage(originalData, entryName, placeholders);
            } else if (documentProcessor.isSupportedDocument(entryName)) {
                logger.debug("Processing document: {} ({} bytes)", entryName, originalData.length);
                return documentProcessor.processDocument(originalData, entryName, placeholders);
            }

            return originalData;

        } catch (Exception e) {
            logger.warn("Failed to process entry {}: {}, using original data", entryName, e.getMessage());
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

    private static class JarEntryData {
        final JarEntry entry;
        final byte[] data;

        JarEntryData(JarEntry entry, byte[] data) {
            this.entry = entry;
            this.data = data;
        }
    }

    private static class ZipEntryData {
        final ZipEntry entry;
        final byte[] data;

        ZipEntryData(ZipEntry entry, byte[] data) {
            this.entry = entry;
            this.data = data;
        }
    }
}
