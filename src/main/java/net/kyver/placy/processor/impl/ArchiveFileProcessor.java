package net.kyver.placy.processor.impl;

import net.kyver.placy.config.EnvironmentSetup;
import net.kyver.placy.core.PlaceholderEngine;
import net.kyver.placy.core.ProcessingResult;
import net.kyver.placy.core.ValidationResult;
import net.kyver.placy.processor.FileProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

    private final PlaceholderEngine engine;
    private final JavaCompiler compiler;

    public ArchiveFileProcessor() {
        this.engine = new PlaceholderEngine();
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            logger.warn("Java compiler not available - .class files in archives will be copied without processing");
        }
        logger.debug("ArchiveFileProcessor initialized with class file support");
    }

    @Override
    public ProcessingResult process(InputStream input,
                                  OutputStream output,
                                  Map<String, String> placeholders,
                                  String filename) {

        logger.debug("Processing archive file: {}", filename);

        long totalBytesProcessed = 0;
        long totalReplacements = 0;
        int filesProcessed = 0;

        boolean isJar = filename != null && filename.toLowerCase().endsWith(".jar");

        try {
            if (isJar) {
                return processJarFile(input, output, placeholders, filename);
            } else {
                return processZipFile(input, output, placeholders, filename);
            }
        } catch (Exception e) {
            logger.error("Failed to process archive {}: {}", filename, e.getMessage(), e);
            throw new RuntimeException("Archive processing failed", e);
        }
    }

    private ProcessingResult processJarFile(InputStream input, OutputStream output,
                                          Map<String, String> placeholders, String filename) throws Exception {

        long totalBytesProcessed = 0;
        long totalReplacements = 0;
        int filesProcessed = 0;

        try (JarInputStream jarIn = new JarInputStream(new BufferedInputStream(input));
             JarOutputStream jarOut = new JarOutputStream(new BufferedOutputStream(output))) {

            if (jarIn.getManifest() != null) {
                jarOut.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jarIn.getManifest().write(jarOut);
                jarOut.closeEntry();
            }

            JarEntry entry;
            while ((entry = jarIn.getNextJarEntry()) != null) {
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    jarIn.closeEntry();
                    continue;
                }

                logger.debug("Processing JAR entry: {}", entry.getName());

                JarEntry newEntry = new JarEntry(entry.getName());
                newEntry.setTime(entry.getTime());
                jarOut.putNextEntry(newEntry);

                if (!entry.isDirectory()) {
                    ProcessingResult entryResult = processArchiveEntry(
                        jarIn, jarOut, entry, placeholders, true);

                    totalBytesProcessed += entryResult.getBytesProcessed();
                    totalReplacements += entryResult.getReplacementCount();
                    filesProcessed++;
                }

                jarIn.closeEntry();
                jarOut.closeEntry();
            }

            jarOut.finish();

            ProcessingResult result = new ProcessingResult(totalBytesProcessed, totalReplacements, placeholders.size());
            logger.info("JAR processing completed: {} files processed, {} replacements made",
                       filesProcessed, totalReplacements);

            return result;
        }
    }

    private ProcessingResult processZipFile(InputStream input, OutputStream output,
                                          Map<String, String> placeholders, String filename) throws Exception {

        long totalBytesProcessed = 0;
        long totalReplacements = 0;
        int filesProcessed = 0;

        try (ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(input));
             ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(output))) {

            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                logger.debug("Processing ZIP entry: {}", entry.getName());

                ZipEntry newEntry = new ZipEntry(entry.getName());
                newEntry.setTime(entry.getTime());
                zipOut.putNextEntry(newEntry);

                if (!entry.isDirectory()) {
                    ProcessingResult entryResult = processArchiveEntry(
                        zipIn, zipOut, entry, placeholders, false);

                    totalBytesProcessed += entryResult.getBytesProcessed();
                    totalReplacements += entryResult.getReplacementCount();
                    filesProcessed++;
                }

                zipIn.closeEntry();
                zipOut.closeEntry();
            }

            zipOut.finish();

            ProcessingResult result = new ProcessingResult(totalBytesProcessed, totalReplacements, placeholders.size());
            logger.debug("ZIP processing completed: {} files processed", filesProcessed);

            return result;
        }
    }

    private ProcessingResult processArchiveEntry(InputStream input, OutputStream output,
                                               ZipEntry entry, Map<String, String> placeholders,
                                               boolean isJar) throws Exception {
        long bytesProcessed = 0;
        long replacements = 0;

        if (isArchive(entry.getName()) && EnvironmentSetup.isRecursiveArchivesEnabled()) {
            logger.debug("Recursively processing nested archive: {}", entry.getName());

            ByteArrayOutputStream nestedOutput = new ByteArrayOutputStream();
            ProcessingResult nestedResult;

            if (isJar) {
                nestedResult = processJarFile(input, nestedOutput, placeholders, entry.getName());
            } else {
                nestedResult = processZipFile(input, nestedOutput, placeholders, entry.getName());
            }

            bytesProcessed += nestedResult.getBytesProcessed();
            replacements += nestedResult.getReplacementCount();

            output.write(nestedOutput.toByteArray());
        } else if (isTextFile(entry.getName())) {
            logger.debug("Processing text file: {}", entry.getName());
            ProcessingResult result = processTextEntry(input, output, placeholders);
            bytesProcessed = result.getBytesProcessed();
            replacements = result.getReplacementCount();
        } else if (isClassFile(entry.getName())) {
            logger.debug("Processing class file: {}", entry.getName());
            ProcessingResult result = processClassFile(input, output, entry.getName(), placeholders);
            bytesProcessed = result.getBytesProcessed();
            replacements = result.getReplacementCount();
        } else {
            logger.debug("Processing regular file: {}", entry.getName());
            bytesProcessed = input.transferTo(output);
        }

        return new ProcessingResult(bytesProcessed, replacements, placeholders.size());
    }

    private boolean isArchive(String filename) {
        String lowerCaseName = filename.toLowerCase();
        return lowerCaseName.endsWith(".zip") || lowerCaseName.endsWith(".jar") ||
               lowerCaseName.endsWith(".war") || lowerCaseName.endsWith(".ear");
    }

    private ProcessingResult processRegularFile(InputStream input, OutputStream output,
                                              Map<String, String> placeholders) throws IOException {
        long bytesTransferred = input.transferTo(output);
        return new ProcessingResult(bytesTransferred, 0, 0);
    }

    private ProcessingResult processTextEntry(InputStream input, OutputStream output,
                                            Map<String, String> placeholders) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        input.transferTo(buffer);

        ByteArrayInputStream bufferedInput = new ByteArrayInputStream(buffer.toByteArray());
        ByteArrayOutputStream processedOutput = new ByteArrayOutputStream();

        ProcessingResult result = engine.processStream(bufferedInput, processedOutput, placeholders);
        output.write(processedOutput.toByteArray());

        return result;
    }

    private ProcessingResult processClassFile(InputStream input, OutputStream output,
                                            String className, Map<String, String> placeholders) throws IOException {
        try {
            ByteArrayOutputStream classBuffer = new ByteArrayOutputStream();
            input.transferTo(classBuffer);
            byte[] classBytes = classBuffer.toByteArray();

            ByteArrayInputStream bais = new ByteArrayInputStream(classBytes);
            DataInputStream dis = new DataInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
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
                        for (Map.Entry<String, String> p : placeholders.entrySet()) {
                            String oldValue = p.getKey();
                            String newValue = p.getValue();
                            if (newS.contains(oldValue)) {
                                int count = 0;
                                int idx = 0;
                                while ((idx = newS.indexOf(oldValue, idx)) != -1) {
                                    count++;
                                    idx += oldValue.length();
                                }
                                if (count > 0) {
                                    newS = newS.replace(oldValue, newValue);
                                    replacements += count;
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
            ByteArrayOutputStream fallbackBuffer = new ByteArrayOutputStream();
            input.transferTo(fallbackBuffer);
            output.write(fallbackBuffer.toByteArray());
            return new ProcessingResult(fallbackBuffer.size(), 0, 0);
        }
    }

    private int findByteSequence(byte[] array, byte[] sequence, int startIndex) {
        if (sequence.length == 0 || startIndex >= array.length) {
            return -1;
        }

        outer: for (int i = startIndex; i <= array.length - sequence.length; i++) {
            for (int j = 0; j < sequence.length; j++) {
                if (array[i + j] != sequence[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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
        return "ArchiveFileProcessor";
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
        return Math.min(fileSize / 4, 50 * 1024 * 1024);
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot + 1);
    }
}
