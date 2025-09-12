package net.kyver.placy.processor;

import net.kyver.placy.core.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FileProcessorRegistry {
    private static final Logger logger = LoggerFactory.getLogger(FileProcessorRegistry.class);

    private final List<FileProcessor> processors;
    private final Map<String, FileProcessor> extensionCache;
    private final Map<String, FileProcessor> mimeTypeCache;

    @Autowired
    public FileProcessorRegistry(List<FileProcessor> processors) {
        this.processors = new ArrayList<>(processors);
        this.extensionCache = new ConcurrentHashMap<>();
        this.mimeTypeCache = new ConcurrentHashMap<>();

        this.processors.sort((p1, p2) -> Integer.compare(p2.getPriority(), p1.getPriority()));
    }

    public FileProcessor findProcessor(String filename, String mimeType) {
        if (filename != null) {
            String extension = getFileExtension(filename).toLowerCase();
            if (!extension.isEmpty()) {
                FileProcessor cached = extensionCache.get(extension);
                if (cached != null) {
                    return cached;
                }
            }
        }

        if (mimeType != null) {
            FileProcessor cached = mimeTypeCache.get(mimeType.toLowerCase());
            if (cached != null) {
                return cached;
            }
        }

        for (FileProcessor processor : processors) {
            if (processor.canProcess(filename, mimeType)) {
                if (filename != null) {
                    String extension = getFileExtension(filename).toLowerCase();
                    if (!extension.isEmpty()) {
                        extensionCache.put(extension, processor);
                    }
                }
                if (mimeType != null) {
                    mimeTypeCache.put(mimeType.toLowerCase(), processor);
                }

                logger.debug("Selected processor {} for file: {}", processor.getProcessorName(), filename);
                return processor;
            }
        }

        logger.debug("No processor found for file: {}, MIME type: {}", filename, mimeType);
        return null;
    }

    public List<FileProcessor> getProcessorsForExtension(String extension) {
        List<FileProcessor> result = new ArrayList<>();
        String lowerExt = extension.toLowerCase();

        for (FileProcessor processor : processors) {
            if (processor.getSupportedExtensions().contains(lowerExt)) {
                result.add(processor);
            }
        }

        return result;
    }

    public List<FileProcessor> getProcessorsForMimeType(String mimeType) {
        List<FileProcessor> result = new ArrayList<>();
        String lowerMime = mimeType.toLowerCase();

        for (FileProcessor processor : processors) {
            if (processor.getSupportedMimeTypes().contains(lowerMime)) {
                result.add(processor);
            }
        }

        return result;
    }

    public ValidationResult validateFile(String filename, String mimeType, Map<String, String> placeholders) {
        FileProcessor processor = findProcessor(filename, mimeType);

        if (processor == null) {
            ValidationResult result = new ValidationResult();
            result.addError("No processor found for file: " + filename + " (MIME: " + mimeType + ")");
            return result;
        }

        return processor.validate(filename, placeholders);
    }

    public List<FileProcessor> getAllProcessors() {
        return Collections.unmodifiableList(processors);
    }

    public Set<String> getAllSupportedExtensions() {
        Set<String> extensions = new HashSet<>();
        for (FileProcessor processor : processors) {
            extensions.addAll(processor.getSupportedExtensions());
        }
        return extensions;
    }

    public Set<String> getAllSupportedMimeTypes() {
        Set<String> mimeTypes = new HashSet<>();
        for (FileProcessor processor : processors) {
            mimeTypes.addAll(processor.getSupportedMimeTypes());
        }
        return mimeTypes;
    }

    public void clearCache() {
        extensionCache.clear();
        mimeTypeCache.clear();
        logger.debug("Processor caches cleared");
    }

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("extensionCacheSize", extensionCache.size());
        stats.put("mimeTypeCacheSize", mimeTypeCache.size());
        stats.put("totalProcessors", processors.size());
        return stats;
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot + 1);
    }
}
