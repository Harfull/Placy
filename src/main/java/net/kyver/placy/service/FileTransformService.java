package net.kyver.placy.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class FileTransformService {

    private static final Logger logger = LoggerFactory.getLogger(FileTransformService.class);

    private final Set<String> knownTextExtensions;
    private final Set<String> supportedArchiveExtensions;
    private final Set<String> imageExtensions;
    private final Set<String> documentExtensions;

    public FileTransformService() {
        this.knownTextExtensions = loadExtensions("supported/text.txt");
        this.supportedArchiveExtensions = loadExtensions("supported/archives.txt");
        this.imageExtensions = loadExtensions("supported/images.txt");
        this.documentExtensions = loadExtensions("supported/documents.txt");

        logger.info("Loaded {} text extensions, {} archive extensions, {} image extensions",
                knownTextExtensions.size(), supportedArchiveExtensions.size(), imageExtensions.size());
    }

    private Set<String> loadExtensions(String resourcePath) {
        Set<String> extensions = new HashSet<>();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.warn("Could not find resource: {}", resourcePath);
                return extensions;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("//")) {
                        extensions.add(line.toLowerCase());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load extensions from {}: {}", resourcePath, e.getMessage());
        }
        return extensions;
    }

    public byte[] transformFile(MultipartFile file, Map<String, String> placeholders) throws IOException {
        String filename = Objects.requireNonNull(file.getOriginalFilename(), "File must have a name");
        logger.info("Transforming file: {} with {} placeholders", filename, placeholders.size());

        byte[] fileBytes = file.getBytes();
        String extension = getFileExtension(filename).toLowerCase();

        if (supportedArchiveExtensions.contains(extension)) {
            return transformArchive(fileBytes, placeholders, extension);
        } else if (isTextBasedFile(filename, fileBytes)) {
            return transformTextFile(fileBytes, placeholders);
        } else {
            throw new IllegalArgumentException("Unsupported file type: " + filename);
        }
    }

    private boolean isTextBasedFile(String filename, byte[] bytes) {
        String extension = getFileExtension(filename).toLowerCase();
        return knownTextExtensions.contains(extension) || !isTextFile(bytes);
    }

    private boolean isTextFile(byte[] bytes) {
        if (bytes.length == 0) return true;

        int len = Math.min(bytes.length, 1024);
        int controlChars = 0;

        for (int i = 0; i < len; i++) {
            byte b = bytes[i];
            if (b == 0) return false;
            if ((b >= 0x20 && b <= 0x7E) || (b & 0x80) != 0 || b == 0x09 || b == 0x0A || b == 0x0D) continue;
            if (b < 0x20 && b != 0x1B) controlChars++;
        }

        return ((double) controlChars / len) < 0.1;
    }

    private byte[] transformArchive(byte[] bytes, Map<String, String> placeholders, String extension) throws IOException {
        if (".jar".equals(extension)) return transformJarFile(bytes, placeholders);
        else return transformZipFile(bytes, placeholders);
    }

    private byte[] transformJarFile(byte[] jarBytes, Map<String, String> placeholders) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(jarBytes);
             ByteArrayOutputStream baos = new ByteArrayOutputStream();
             JarInputStream jis = new JarInputStream(bais)) {

            Manifest manifest = jis.getManifest();
            try (JarOutputStream jos = (manifest != null) ?
                new JarOutputStream(baos, manifest) :
                new JarOutputStream(baos)) {

                Map<String, byte[]> processedEntries = new LinkedHashMap<>();
                JarEntry entry;

                while ((entry = jis.getNextJarEntry()) != null) {
                    if (entry.isDirectory()) {
                        processedEntries.put(entry.getName(), null);
                        continue;
                    }

                    byte[] entryBytes = readEntryBytes(jis);
                    processedEntries.put(entry.getName(), entryBytes);
                }

                try (JarInputStream jis2 = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
                    JarEntry originalEntry;
                    while ((originalEntry = jis2.getNextJarEntry()) != null) {
                        if (originalEntry.isDirectory()) {
                            JarEntry dirEntry = new JarEntry(originalEntry.getName());
                            copyEntryAttributes(originalEntry, dirEntry);
                            jos.putNextEntry(dirEntry);
                            jos.closeEntry();
                            continue;
                        }

                        String entryName = originalEntry.getName();
                        byte[] originalBytes = processedEntries.get(entryName);
                        if (originalBytes == null) continue;

                        byte[] transformedBytes = processEntryContent(entryName, originalBytes, placeholders);

                        JarEntry newEntry = new JarEntry(entryName);
                        copyEntryAttributes(originalEntry, newEntry);

                        newEntry.setSize(transformedBytes.length);
                        if (newEntry.getMethod() == ZipEntry.STORED) {
                            newEntry.setCrc(calculateCRC32(transformedBytes));
                        }

                        jos.putNextEntry(newEntry);
                        jos.write(transformedBytes);
                        jos.closeEntry();
                    }
                }

                jos.finish();
                logger.info("Successfully transformed JAR file with {} placeholders", placeholders.size());
                return baos.toByteArray();
            }
        }
    }

    private byte[] transformZipFile(byte[] zipBytes, Map<String, String> placeholders) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(zipBytes);
             ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipInputStream zis = new ZipInputStream(bais);
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zos.putNextEntry(new ZipEntry(entry.getName()));
                    zos.closeEntry();
                    continue;
                }

                byte[] entryBytes = zis.readAllBytes();
                byte[] transformedBytes = isTextFileInArchive(entry.getName(), entryBytes) ?
                    transformTextFile(entryBytes, placeholders) : entryBytes;

                ZipEntry newEntry = new ZipEntry(entry.getName());
                newEntry.setTime(entry.getTime());
                zos.putNextEntry(newEntry);
                zos.write(transformedBytes);
                zos.closeEntry();
            }
            logger.info("Transformed ZIP file with {} placeholders", placeholders.size());
            return baos.toByteArray();
        }
    }

    private boolean isTextFileInArchive(String filename, byte[] bytes) {
        String extension = getFileExtension(filename).toLowerCase();

        return knownTextExtensions.contains(extension) && isTextFile(bytes);
    }

    private byte[] transformTextFile(byte[] textBytes, Map<String, String> placeholders) {
        String content = new String(textBytes);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            content = content.replace(entry.getKey(), entry.getValue());
        }
        return content.getBytes();
    }

    private byte[] replacePlaceholdersInClass(byte[] classBytes, Map<String, String> replacements) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String str) {
                            for (Map.Entry<String, String> e : replacements.entrySet()) {
                                if (str.contains(e.getKey())) str = str.replace(e.getKey(), e.getValue());
                            }
                            super.visitLdcInsn(str);
                        } else {
                            super.visitLdcInsn(value);
                        }
                    }
                };
            }
        };

        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot) : "";
    }

    private byte[] readEntryBytes(JarInputStream jis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = jis.read(buffer)) > 0) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    private void copyEntryAttributes(JarEntry source, JarEntry target) {
        target.setTime(source.getTime());
        target.setMethod(source.getMethod());
        if (source.getComment() != null) {
            target.setComment(source.getComment());
        }
        if (source.getExtra() != null) {
            target.setExtra(source.getExtra());
        }
    }

    private byte[] processEntryContent(String entryName, byte[] originalBytes, Map<String, String> placeholders) {
        if (entryName.endsWith(".class")) {
            return replacePlaceholdersInClass(originalBytes, placeholders);
        } else if (isTextFileInArchive(entryName, originalBytes)) {
            return transformTextFile(originalBytes, placeholders);
        } else {
            return originalBytes;
        }
    }

    private long calculateCRC32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    public Map<String, String> parsePlaceholders(String json) {
        if (json == null || json.trim().isEmpty()) return new HashMap<>();
        try {
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            return new Gson().fromJson(json, type);
        } catch (Exception e) {
            logger.error("Failed to parse placeholders JSON: {}", json, e);
            throw new IllegalArgumentException("Invalid JSON format", e);
        }
    }

    public boolean isSupportedFileType(String filename) {
        if (filename == null) return false;
        String extension = getFileExtension(filename).toLowerCase();
        return supportedArchiveExtensions.contains(extension)
                || knownTextExtensions.contains(extension);
    }
}
