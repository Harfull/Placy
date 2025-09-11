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
        List<JarEntryProcessingTask> tasks = new ArrayList<>();

        JarEntry entry;
        while ((entry = jarIn.getNextJarEntry()) != null) {
            if (entry.isDirectory()) {
                JarEntry newEntry = new JarEntry(entry.getName());
                copyJarEntryMetadata(entry, newEntry);
                jarOut.putNextEntry(newEntry);
                jarOut.closeEntry();
            } else {
                byte[] data = readEntryData(jarIn);
                tasks.add(new JarEntryProcessingTask(entry, data));
            }
        }

        if (tasks.isEmpty()) {
            return;
        }

        Map<String, byte[]> results = tasks.parallelStream()
                .collect(ConcurrentHashMap::new,
                        (map, task) -> {
                            try {
                                byte[] processedData = processEntryContent(task.entry.getName(), task.data, placeholders);
                                map.put(task.entry.getName(), processedData);
                            } catch (Exception e) {
                                logger.warn("Failed to process entry {}: {}", task.entry.getName(), e.getMessage());
                                map.put(task.entry.getName(), task.data);
                            }
                        },
                        Map::putAll);

        for (JarEntryProcessingTask task : tasks) {
            JarEntry originalEntry = task.entry;
            String entryName = originalEntry.getName();
            byte[] processedBytes = results.get(entryName);

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

        logger.debug("Processed {} JAR entries using parallel streams", tasks.size());
    }

    private void processZipEntriesAsync(ZipInputStream zipIn, ZipOutputStream zipOut, Map<String, String> placeholders) throws IOException {
        List<ZipEntryProcessingTask> tasks = new ArrayList<>();

        ZipEntry entry;
        while ((entry = zipIn.getNextEntry()) != null) {
            if (entry.isDirectory()) {
                ZipEntry newEntry = new ZipEntry(entry.getName());
                copyZipEntryMetadata(entry, newEntry);
                zipOut.putNextEntry(newEntry);
                zipOut.closeEntry();
            } else {
                byte[] data = readEntryData(zipIn);
                tasks.add(new ZipEntryProcessingTask(entry, data));
            }
        }

        if (tasks.isEmpty()) {
            return;
        }

        Map<String, byte[]> results = tasks.parallelStream()
                .collect(ConcurrentHashMap::new,
                        (map, task) -> {
                            try {
                                byte[] processedData = processEntryContent(task.entry.getName(), task.data, placeholders);
                                map.put(task.entry.getName(), processedData);
                            } catch (Exception e) {
                                logger.warn("Failed to process entry {}: {}", task.entry.getName(), e.getMessage());
                                map.put(task.entry.getName(), task.data);
                            }
                        },
                        Map::putAll);

        for (ZipEntryProcessingTask task : tasks) {
            ZipEntry originalEntry = task.entry;
            String entryName = originalEntry.getName();
            byte[] processedBytes = results.get(entryName);

            ZipEntry newEntry = new ZipEntry(originalEntry.getName());
            newEntry.setTime(originalEntry.getTime());
            if (originalEntry.getComment() != null) {
                newEntry.setComment(originalEntry.getComment());
            }
            if (originalEntry.getExtra() != null) {
                newEntry.setExtra(originalEntry.getExtra());
            }

            newEntry.setMethod(ZipEntry.DEFLATED);

            zipOut.putNextEntry(newEntry);
            zipOut.write(processedBytes);
            zipOut.closeEntry();
        }

        logger.debug("Processed {} ZIP entries using parallel streams", tasks.size());
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

    private byte[] readEntryData(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[65536];
        ByteArrayOutputStream output = new ByteArrayOutputStream(65536);

        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }

        return output.toByteArray();
    }

    private byte[] processEntryContent(String entryName, byte[] originalData, Map<String, String> placeholders) {
        if (originalData == null || originalData.length == 0) {
            return originalData;
        }

        if (placeholders.isEmpty() || isSkippableFile(entryName)) {
            return originalData;
        }

        try {
            if (entryName.endsWith(".class")) {
                return classProcessor.replacePlaceholdersInClass(originalData, placeholders);
            } else if (isTextFile(entryName, originalData)) {
                if (containsAnyPlaceholder(originalData, placeholders)) {
                    return streamProcessor.transformTextStream(originalData, placeholders);
                }
                return originalData;
            } else if (isArchiveFile(entryName)) {
                return processArchive(originalData, entryName, placeholders);
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

    private static final Set<String> SKIPPABLE_EXTENSIONS = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
        ".mp3", ".mp4", ".avi", ".mov", ".wav", ".ogg",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".ttf", ".otf", ".woff", ".woff2", ".eot",
        ".dll", ".so", ".dylib", ".exe", ".bin", ".dat"
    );

    private boolean isSkippableFile(String filename) {
        String extension = getFileExtension(filename).toLowerCase();
        return SKIPPABLE_EXTENSIONS.contains(extension);
    }

    private boolean containsAnyPlaceholder(byte[] data, Map<String, String> placeholders) {
        if (placeholders.isEmpty() || data.length == 0) {
            return false;
        }

        for (String placeholder : placeholders.keySet()) {
            if (placeholder.length() > data.length) continue;

            byte[] needleBytes = placeholder.getBytes();
            if (boyerMooreSearch(data, needleBytes) != -1) {
                return true;
            }
        }
        return false;
    }

    private int boyerMooreSearch(byte[] text, byte[] pattern) {
        if (pattern.length == 0) return 0;
        if (pattern.length > text.length) return -1;

        int[] badChar = new int[256];
        Arrays.fill(badChar, pattern.length);
        for (int i = 0; i < pattern.length - 1; i++) {
            badChar[pattern[i] & 0xFF] = pattern.length - 1 - i;
        }

        int shift = 0;
        while (shift <= text.length - pattern.length) {
            int j = pattern.length - 1;

            while (j >= 0 && pattern[j] == text[shift + j]) {
                j--;
            }

            if (j < 0) {
                return shift;
            } else {
                shift += Math.max(1, badChar[text[shift + pattern.length - 1] & 0xFF]);
            }
        }

        return -1;
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

    private static class JarEntryProcessingTask {
        final JarEntry entry;
        final byte[] data;

        JarEntryProcessingTask(JarEntry entry, byte[] data) {
            this.entry = entry;
            this.data = data;
        }
    }

    private static class ZipEntryProcessingTask {
        final ZipEntry entry;
        final byte[] data;

        ZipEntryProcessingTask(ZipEntry entry, byte[] data) {
            this.entry = entry;
            this.data = data;
        }
    }
}
