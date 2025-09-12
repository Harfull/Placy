package net.kyver.placy.processor.impl;

import net.kyver.placy.config.EnvironmentSetup;
import net.kyver.placy.core.PlaceholderEngine;
import net.kyver.placy.core.ProcessingResult;
import net.kyver.placy.core.ValidationResult;
import net.kyver.placy.core.replacement.ParallelReplacementStrategy;
import net.kyver.placy.processor.FileProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.tools.*;
import java.io.*;
 import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Component
public class ArchiveFileProcessor implements FileProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ArchiveFileProcessor.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "zip", "jar", "war", "ear"
    );

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
        "application/zip", "application/java-archive",
        "application/x-zip-compressed", "application/octet-stream"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "txt", "json", "xml", "html", "css", "js", "java", "kt", "py", "yaml", "yml",
        "properties", "md", "conf", "config", "sql", "sh", "bat"
    );

    // High-performance configuration
    private static final int BUFFER_SIZE = 2 * 1024 * 1024; // 2MB buffers
    private static final int PARALLEL_THRESHOLD = 50 * 1024; // 50KB threshold for parallel processing
    private static final int MAX_CONCURRENT_ENTRIES = Runtime.getRuntime().availableProcessors() * 2;

    private final PlaceholderEngine engine;
    private final PlaceholderEngine parallelEngine;
    private final JavaCompiler compiler;
    private final ForkJoinPool processingPool;

    public ArchiveFileProcessor() {
        this.engine = new PlaceholderEngine();
        this.parallelEngine = new PlaceholderEngine(new ParallelReplacementStrategy());
        this.compiler = ToolProvider.getSystemJavaCompiler();
        this.processingPool = new ForkJoinPool(MAX_CONCURRENT_ENTRIES);

        if (compiler == null) {
            logger.warn("Java compiler not available - .class files in archives will be copied without processing");
        }
        logger.debug("High-performance ArchiveFileProcessor initialized with {} threads", MAX_CONCURRENT_ENTRIES);
    }

    @Override
    public ProcessingResult process(InputStream input,
                                  OutputStream output,
                                  Map<String, String> placeholders,
                                  String filename) {

        logger.debug("Processing archive file: {} with high-performance mode", filename);

        boolean isJar = filename != null && filename.toLowerCase().endsWith(".jar");

        try {
            if (isJar) {
                return processJarFileOptimized(input, output, placeholders, filename);
            } else {
                return processZipFileOptimized(input, output, placeholders, filename);
            }
        } catch (Exception e) {
            logger.error("Failed to process archive {}: {}", filename, e.getMessage(), e);
            throw new RuntimeException("Archive processing failed", e);
        }
    }

    private ProcessingResult processJarFileOptimized(InputStream input, OutputStream output,
                                                   Map<String, String> placeholders, String filename) throws Exception {

        long totalBytesProcessed = 0;
        long totalReplacements = 0;
        int filesProcessed = 0;

        try (JarInputStream jarIn = new JarInputStream(new BufferedInputStream(input, BUFFER_SIZE));
             JarOutputStream jarOut = new JarOutputStream(new BufferedOutputStream(output, BUFFER_SIZE))) {

            // Handle manifest first
            if (jarIn.getManifest() != null) {
                jarOut.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jarIn.getManifest().write(jarOut);
                jarOut.closeEntry();
            }

            // Collect all entries for potential parallel processing
            List<ArchiveEntryTask> entryTasks = new ArrayList<>();
            JarEntry entry;

            while ((entry = jarIn.getNextJarEntry()) != null) {
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    jarIn.closeEntry();
                    continue;
                }

                if (!entry.isDirectory()) {
                    // Read entry data into memory for parallel processing
                    ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
                    transferOptimized(jarIn, entryBuffer);
                    byte[] entryData = entryBuffer.toByteArray();

                    entryTasks.add(new ArchiveEntryTask(entry, entryData, placeholders, true));
                    totalBytesProcessed += entryData.length;
                    filesProcessed++;
                }
                jarIn.closeEntry();
            }

            // Process entries in parallel for better performance
            List<CompletableFuture<ArchiveEntryResult>> futures = new ArrayList<>();

            for (ArchiveEntryTask task : entryTasks) {
                CompletableFuture<ArchiveEntryResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return processEntryTask(task);
                    } catch (Exception e) {
                        logger.error("Failed to process entry {}: {}", task.entry.getName(), e.getMessage());
                        return new ArchiveEntryResult(task.entry, task.originalData, 0);
                    }
                }, processingPool);

                futures.add(future);
            }

            // Write processed entries to output
            for (CompletableFuture<ArchiveEntryResult> future : futures) {
                ArchiveEntryResult result = future.get(30, TimeUnit.SECONDS);

                JarEntry newEntry = new JarEntry(result.entry.getName());
                newEntry.setTime(result.entry.getTime());
                jarOut.putNextEntry(newEntry);
                jarOut.write(result.processedData);
                jarOut.closeEntry();

                totalReplacements += result.replacements;
            }

            jarOut.finish();

            ProcessingResult result = new ProcessingResult(totalBytesProcessed, totalReplacements, placeholders.size());
            logger.info("High-performance JAR processing completed: {} files processed, {} replacements made in parallel",
                       filesProcessed, totalReplacements);

            return result;
        }
    }

    private ProcessingResult processZipFileOptimized(InputStream input, OutputStream output,
                                                   Map<String, String> placeholders, String filename) throws Exception {

        long totalBytesProcessed = 0;
        long totalReplacements = 0;
        int filesProcessed = 0;

        try (ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(input, BUFFER_SIZE));
             ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(output, BUFFER_SIZE))) {

            // Collect all entries for potential parallel processing
            List<ArchiveEntryTask> entryTasks = new ArrayList<>();
            ZipEntry entry;

            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    // Read entry data into memory for parallel processing
                    ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
                    transferOptimized(zipIn, entryBuffer);
                    byte[] entryData = entryBuffer.toByteArray();

                    entryTasks.add(new ArchiveEntryTask(entry, entryData, placeholders, false));
                    totalBytesProcessed += entryData.length;
                    filesProcessed++;
                }
                zipIn.closeEntry();
            }

            // Process entries in parallel
            List<CompletableFuture<ArchiveEntryResult>> futures = new ArrayList<>();

            for (ArchiveEntryTask task : entryTasks) {
                CompletableFuture<ArchiveEntryResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return processEntryTask(task);
                    } catch (Exception e) {
                        logger.error("Failed to process entry {}: {}", task.entry.getName(), e.getMessage());
                        return new ArchiveEntryResult(task.entry, task.originalData, 0);
                    }
                }, processingPool);

                futures.add(future);
            }

            // Write processed entries to output
            for (CompletableFuture<ArchiveEntryResult> future : futures) {
                ArchiveEntryResult result = future.get(30, TimeUnit.SECONDS);

                ZipEntry newEntry = new ZipEntry(result.entry.getName());
                newEntry.setTime(result.entry.getTime());
                zipOut.putNextEntry(newEntry);
                zipOut.write(result.processedData);
                zipOut.closeEntry();

                totalReplacements += result.replacements;
            }

            zipOut.finish();

            ProcessingResult finalResult = new ProcessingResult(totalBytesProcessed, totalReplacements, placeholders.size());
            logger.debug("High-performance ZIP processing completed: {} files processed in parallel", filesProcessed);

            return finalResult;
        }
    }

    private ArchiveEntryResult processEntryTask(ArchiveEntryTask task) throws Exception {
        ZipEntry entry = task.entry;
        byte[] originalData = task.originalData;
        Map<String, String> placeholders = task.placeholders;
        boolean isJar = task.isJar;

        if (isArchive(entry.getName()) && EnvironmentSetup.isRecursiveArchivesEnabled()) {
            logger.debug("Recursively processing nested archive: {}", entry.getName());

            try (ByteArrayInputStream input = new ByteArrayInputStream(originalData);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(originalData.length + (originalData.length >> 2))) {

                ProcessingResult nestedResult;
                if (isJar) {
                    nestedResult = processJarFileOptimized(input, output, placeholders, entry.getName());
                } else {
                    nestedResult = processZipFileOptimized(input, output, placeholders, entry.getName());
                }

                return new ArchiveEntryResult(entry, output.toByteArray(), nestedResult.getReplacementCount());
            }
        } else if (isTextFile(entry.getName())) {
            logger.debug("Processing text file: {}", entry.getName());

            try (ByteArrayInputStream input = new ByteArrayInputStream(originalData);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(originalData.length + (originalData.length >> 2))) {

                PlaceholderEngine engineToUse = originalData.length > PARALLEL_THRESHOLD ? parallelEngine : engine;
                ProcessingResult result = engineToUse.processStream(input, output, placeholders);

                return new ArchiveEntryResult(entry, output.toByteArray(), result.getReplacementCount());
            }
        } else if (isClassFile(entry.getName())) {
            logger.debug("Processing class file: {}", entry.getName());

            try (ByteArrayInputStream input = new ByteArrayInputStream(originalData);
                 ByteArrayOutputStream output = new ByteArrayOutputStream(originalData.length + (originalData.length >> 2))) {

                ProcessingResult result = processClassFileOptimized(input, output, entry.getName(), placeholders);
                return new ArchiveEntryResult(entry, output.toByteArray(), result.getReplacementCount());
            }
        } else {
            logger.debug("Copying regular file without processing: {}", entry.getName());
            return new ArchiveEntryResult(entry, originalData, 0);
        }
    }

    private ProcessingResult processClassFileOptimized(InputStream input, OutputStream output,
                                                     String className, Map<String, String> placeholders) throws IOException {
        try {
            ByteArrayOutputStream classBuffer = new ByteArrayOutputStream();
            transferOptimized(input, classBuffer);
            byte[] classBytes = classBuffer.toByteArray();

            ByteArrayInputStream bais = new ByteArrayInputStream(classBytes);
            DataInputStream dis = new DataInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(classBytes.length + (classBytes.length >> 2));
            DataOutputStream dos = new DataOutputStream(baos);

            int magic = dis.readInt();
            int minor = dis.readUnsignedShort();
            int major = dis.readUnsignedShort();
            dos.writeInt(magic);
            dos.writeShort(minor);
            dos.writeShort(major);

            int cpCount = dis.readUnsignedShort();
            dos.writeShort(cpCount);

            int replacements = 0;

            // Pre-sorted placeholders for better performance
            List<Map.Entry<String, String>> sortedPlaceholders = placeholders.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getKey().length(), e1.getKey().length()))
                .toList();

            for (int i = 1; i < cpCount; i++) {
                int tag = dis.readUnsignedByte();
                dos.writeByte(tag);
                switch (tag) {
                    case 1: {
                        int len = dis.readUnsignedShort();
                        byte[] bytes = new byte[len];
                        dis.readFully(bytes);
                        String s = new String(bytes, StandardCharsets.UTF_8);

                        String newS = s;
                        for (Map.Entry<String, String> p : sortedPlaceholders) {
                            String oldValue = p.getKey();
                            String newValue = p.getValue();
                            if (newS.contains(oldValue)) {
                                int beforeLength = newS.length();
                                newS = newS.replace(oldValue, newValue);
                                int afterLength = newS.length();

                                // Count actual replacements more efficiently
                                if (beforeLength != afterLength) {
                                    int lengthDiff = newValue.length() - oldValue.length();
                                    if (lengthDiff != 0) {
                                        replacements += (afterLength - beforeLength) / lengthDiff;
                                    } else {
                                        replacements += (beforeLength - afterLength) / oldValue.length();
                                    }
                                }
                            }
                        }

                        byte[] newBytes = newS.getBytes(StandardCharsets.UTF_8);
                        dos.writeShort(newBytes.length);
                        dos.write(newBytes);
                        break;
                    }
                    case 3:
                    case 4: {
                        int val = dis.readInt();
                        dos.writeInt(val);
                        break;
                    }
                    case 5:
                    case 6: {
                        long v = dis.readLong();
                        dos.writeLong(v);
                        i++;
                        break;
                    }
                    case 7:
                    case 8: {
                        int idx = dis.readUnsignedShort();
                        dos.writeShort(idx);
                        break;
                    }
                    case 9:
                    case 10:
                    case 11:
                    case 12: {
                        int a = dis.readUnsignedShort();
                        int b = dis.readUnsignedShort();
                        dos.writeShort(a);
                        dos.writeShort(b);
                        break;
                    }
                    case 15: {
                        int refKind = dis.readUnsignedByte();
                        int refIndex = dis.readUnsignedShort();
                        dos.writeByte(refKind);
                        dos.writeShort(refIndex);
                        break;
                    }
                    case 16: {
                        int descIndex = dis.readUnsignedShort();
                        dos.writeShort(descIndex);
                        break;
                    }
                    case 18: {
                        int bsm = dis.readUnsignedShort();
                        int nameAndType = dis.readUnsignedShort();
                        dos.writeShort(bsm);
                        dos.writeShort(nameAndType);
                        break;
                    }
                    default: {
                        throw new IOException("Unsupported constant pool tag: " + tag);
                    }
                }
            }

            byte[] remainder = dis.readAllBytes();
            dos.write(remainder);

            byte[] processed = baos.toByteArray();
            output.write(processed);

            logger.debug("Processed class file {} with {} constant-pool replacements", className, replacements);
            return new ProcessingResult(processed.length, replacements, placeholders.size());

        } catch (Exception e) {
            logger.error("Failed to process class file {}: {}", className, e.getMessage());
            transferOptimized(input, output);
            return new ProcessingResult(0, 0, 0);
        }
    }

    // Optimized data transfer with larger buffers
    private void transferOptimized(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }

    private boolean isArchive(String filename) {
        String lowerCaseName = filename.toLowerCase();
        return lowerCaseName.endsWith(".zip") || lowerCaseName.endsWith(".jar") ||
               lowerCaseName.endsWith(".war") || lowerCaseName.endsWith(".ear");
    }

    private boolean isTextFile(String filename) {
        if (filename == null || filename.isEmpty()) {
            return false;
        }

        String extension = getFileExtension(filename).toLowerCase();
        return TEXT_EXTENSIONS.contains(extension);
    }

    private boolean isClassFile(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".class");
    }

    // Helper classes for parallel processing
    private static class ArchiveEntryTask {
        final ZipEntry entry;
        final byte[] originalData;
        final Map<String, String> placeholders;
        final boolean isJar;

        ArchiveEntryTask(ZipEntry entry, byte[] originalData, Map<String, String> placeholders, boolean isJar) {
            this.entry = entry;
            this.originalData = originalData;
            this.placeholders = placeholders;
            this.isJar = isJar;
        }
    }

    private static class ArchiveEntryResult {
        final ZipEntry entry;
        final byte[] processedData;
        final long replacements;

        ArchiveEntryResult(ZipEntry entry, byte[] processedData, long replacements) {
            this.entry = entry;
            this.processedData = processedData;
            this.replacements = replacements;
        }
    }

    @Override
    public Set<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public Set<String> getSupportedMimeTypes() {
        return SUPPORTED_MIME_TYPES;
    }

    @Override
    public boolean canProcess(String filename, String mimeType) {
        if (filename != null) {
            String extension = getFileExtension(filename).toLowerCase();
            if (SUPPORTED_EXTENSIONS.contains(extension)) {
                return true;
            }
        }

        if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType.toLowerCase())) {
            return true;
        }

        return false;
    }

    @Override
    public ValidationResult validate(String filename, Map<String, String> placeholders) {
        ValidationResult result = engine.validatePlaceholders(placeholders);

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.length() > 1024 * 1024) {
                result.addWarning("Large placeholder value may impact archive processing performance: " + entry.getKey());
            }
        }

        return result;
    }

    @Override
    public String getProcessorName() {
        return "HighPerformanceArchiveFileProcessor";
    }

    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public long estimateMemoryUsage(long fileSize) {
        // Higher memory usage for parallel processing but much faster
        return Math.min(fileSize / 2, 200 * 1024 * 1024);
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot + 1);
    }
}
