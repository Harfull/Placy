package net.kyver.placy.service.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class FileTypeDetector {
    private static final Logger logger = LoggerFactory.getLogger(FileTypeDetector.class);

    private final ConcurrentMap<String, Boolean> textFileCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> extensionCache = new ConcurrentHashMap<>();

    private final Set<String> textExtensions;
    private final Set<String> archiveExtensions;
    private final Set<String> imageExtensions;
    private final Set<String> documentExtensions;
    private final Set<String> otherExtensions;

    private static final int TEXT_DETECTION_SAMPLE_SIZE = 512;
    private static final double MAX_CONTROL_CHAR_RATIO = 0.1;
    private static final byte NULL_BYTE = 0;

    public FileTypeDetector() {
        this.textExtensions = new HashSet<>();
        this.archiveExtensions = new HashSet<>();
        this.imageExtensions = new HashSet<>();
        this.documentExtensions = new HashSet<>();
        this.otherExtensions = new HashSet<>();

        loadAllSupportedResources();

        logger.info("FileTypeDetector initialized with {} text, {} archive, {} image, {} document, {} other extensions",
                textExtensions.size(), archiveExtensions.size(), imageExtensions.size(), documentExtensions.size(), otherExtensions.size());
    }

    private void loadAllSupportedResources() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:supported/*.txt");
            if (resources == null || resources.length == 0) {
                logger.warn("No supported resources found under classpath:supported/");
                return;
            }

            for (Resource resource : resources) {
                String filename = resource.getFilename() == null ? "" : resource.getFilename().toLowerCase();
                Set<String> targetSet = pickTargetSet(filename);
                try (InputStream is = resource.getInputStream()) {
                    loadExtensionsFromStream(is, targetSet);
                    logger.debug("Loaded {} entries from resource {}", targetSet.size(), filename);
                } catch (IOException e) {
                    logger.warn("Failed to read resource {}: {}", filename, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load supported resources: {}", e.getMessage());
        }
    }

    private Set<String> pickTargetSet(String filename) {
        if (filename.contains("text")) return textExtensions;
        if (filename.contains("archive") || filename.contains("archives")) return archiveExtensions;
        if (filename.contains("image") || filename.contains("images")) return imageExtensions;
        if (filename.contains("document") || filename.contains("documents")) return documentExtensions;
        return otherExtensions;
    }

    private void loadExtensionsFromStream(InputStream is, Set<String> target) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("//"))
                    .map(String::toLowerCase)
                    .map(line -> line.startsWith(".") ? line : "." + line)
                    .forEach(target::add);
        }
    }

    public String getFileExtension(String filename) {
        if (filename == null) return "";

        return extensionCache.computeIfAbsent(filename, name -> {
            int lastDot = name.lastIndexOf('.');
            return lastDot > 0 ? name.substring(lastDot).toLowerCase() : "";
        });
    }

    public boolean isTextFile(String filename, byte[] bytes) {
        String cacheKey = filename + ":" + bytes.length;
        return textFileCache.computeIfAbsent(cacheKey, key -> {
            String extension = getFileExtension(filename);

            if (textExtensions.contains(extension)) {
                return true;
            }

            return detectTextContent(bytes);
        });
    }

    private boolean detectTextContent(byte[] bytes) {
        if (bytes.length == 0) return true;

        int sampleSize = Math.min(bytes.length, TEXT_DETECTION_SAMPLE_SIZE);
        int controlChars = 0;

        for (int i = 0; i < sampleSize; i++) {
            byte b = bytes[i];

            if (b == NULL_BYTE) return false;

            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x1B) {
                controlChars++;
                if (((double) controlChars / (i + 1)) > MAX_CONTROL_CHAR_RATIO) {
                    return false;
                }
            }
        }

        return ((double) controlChars / sampleSize) < MAX_CONTROL_CHAR_RATIO;
    }

    public boolean isArchiveFile(String filename) {
        String extension = getFileExtension(filename);
        return archiveExtensions.contains(extension);
    }

    public boolean isJarFile(String filename) {
        return ".jar".equals(getFileExtension(filename));
    }

    public boolean isSupportedFileType(String filename) {
        if (filename == null) return false;
        String extension = getFileExtension(filename);
        return archiveExtensions.contains(extension)
                || textExtensions.contains(extension)
                || imageExtensions.contains(extension)
                || documentExtensions.contains(extension)
                || otherExtensions.contains(extension);
    }

    public Set<String> getTextExtensions() {
        return Set.copyOf(textExtensions);
    }

    public Set<String> getArchiveExtensions() {
        return Set.copyOf(archiveExtensions);
    }

    public Set<String> getImageExtensions() {
        return Set.copyOf(imageExtensions);
    }

    public Set<String> getDocumentExtensions() {
        return Set.copyOf(documentExtensions);
    }

    public Set<String> getOtherExtensions() {
        return Set.copyOf(otherExtensions);
    }

    public Set<String> getAllSupportedExtensions() {
        Set<String> all = new HashSet<>();
        all.addAll(textExtensions);
        all.addAll(archiveExtensions);
        all.addAll(imageExtensions);
        all.addAll(documentExtensions);
        all.addAll(otherExtensions);
        return Set.copyOf(all);
    }

    public void clearCaches() {
        textFileCache.clear();
        extensionCache.clear();
    }
}
