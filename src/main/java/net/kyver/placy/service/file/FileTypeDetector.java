package net.kyver.placy.service.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class FileTypeDetector {
    private static final Logger logger = LoggerFactory.getLogger(FileTypeDetector.class);

    private final ConcurrentMap<String, Boolean> textFileCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> extensionCache = new ConcurrentHashMap<>();

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".txt", ".md", ".json", ".xml", ".html", ".htm", ".css", ".js", ".ts", ".jsx", ".tsx",
        ".java", ".py", ".cpp", ".c", ".h", ".hpp", ".cs", ".php", ".rb", ".go", ".rs", ".swift",
        ".kt", ".scala", ".sh", ".bat", ".ps1", ".sql", ".yml", ".yaml", ".properties", ".ini",
        ".cfg", ".conf", ".log", ".csv", ".tsv", ".dockerfile", ".gitignore", ".gitattributes",
        ".editorconfig", ".eslintrc", ".prettierrc", ".babelrc", ".npmrc", ".yarnrc", ".gemfile",
        ".makefile", ".gradle", ".maven", ".pom", ".sbt", ".build", ".cmake", ".ninja"
    );

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
        ".zip", ".jar", ".war", ".ear", ".aar", ".tar", ".gz", ".tgz", ".bz2", ".tbz2",
        ".xz", ".txz", ".7z", ".rar", ".arj", ".cab", ".lzh", ".ace", ".zoo", ".cpio",
        ".apk", ".xpi", ".crx", ".vsix", ".nupkg", ".snupkg", ".deb", ".rpm", ".dmg",
        ".iso", ".img", ".wim", ".swm", ".esd"
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tiff", ".tif", ".svg", ".webp", ".ico",
        ".psd", ".ai", ".eps", ".raw", ".cr2", ".nef", ".arw", ".dng", ".orf", ".rw2",
        ".pef", ".srw", ".x3f", ".raf", ".3fr", ".fff", ".dcr", ".kdc", ".srf", ".mrw"
    );

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt", ".ods", ".odp",
        ".rtf", ".tex", ".latex", ".epub", ".mobi", ".azw", ".azw3", ".fb2", ".lit", ".pdb",
        ".djvu", ".djv", ".cbr", ".cbz", ".cb7", ".cbt", ".cba"
    );

    private static final Set<String> OTHER_EXTENSIONS = Set.of(
        ".class", ".so", ".dll", ".dylib", ".lib", ".a", ".o", ".obj", ".exe", ".msi",
        ".app", ".deb", ".rpm", ".dmg", ".pkg", ".snap", ".flatpak", ".appimage"
    );

    private static final int TEXT_DETECTION_SAMPLE_SIZE = 1024;
    private static final double MAX_CONTROL_CHAR_RATIO = 0.05;
    private static final byte NULL_BYTE = 0;

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};
    private static final byte[] UTF16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};

    public FileTypeDetector() {
        logger.info("FileTypeDetector initialized with {} text, {} archive, {} image, {} document, {} other extensions",
                TEXT_EXTENSIONS.size(), ARCHIVE_EXTENSIONS.size(), IMAGE_EXTENSIONS.size(),
                DOCUMENT_EXTENSIONS.size(), OTHER_EXTENSIONS.size());
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

            if (TEXT_EXTENSIONS.contains(extension)) {
                return true;
            }

            return detectTextContent(bytes);
        });
    }

    private boolean detectTextContent(byte[] bytes) {
        if (bytes.length == 0) return true;

        if (hasBOM(bytes)) {
            return true;
        }

        if (hasBinarySignature(bytes)) {
            return false;
        }

        int sampleSize = Math.min(bytes.length, TEXT_DETECTION_SAMPLE_SIZE);
        int controlChars = 0;
        int printableChars = 0;
        int whitespaceChars = 0;
        int extendedAsciiChars = 0;

        for (int i = 0; i < sampleSize; i++) {
            byte b = bytes[i];
            int unsignedByte = b & 0xFF;

            if (b == NULL_BYTE) return false;

            if (unsignedByte < 0x20) {
                if (b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0C || b == 0x1B) {
                    whitespaceChars++;
                } else {
                    controlChars++;
                    if (((double) controlChars / (i + 1)) > MAX_CONTROL_CHAR_RATIO) {
                        return false;
                    }
                }
            } else if (unsignedByte <= 0x7E) {
                printableChars++;
            } else {
                extendedAsciiChars++;
            }
        }

        double controlRatio = (double) controlChars / sampleSize;
        double printableRatio = (double) printableChars / sampleSize;
        double whitespaceRatio = (double) whitespaceChars / sampleSize;
        double extendedRatio = (double) extendedAsciiChars / sampleSize;

        if (printableRatio > 0.7) return true;
        if (printableRatio + whitespaceRatio > 0.8) return true;

        if (extendedRatio > 0.1 && isValidUTF8(bytes, sampleSize)) {
            return true;
        }

        return controlRatio < MAX_CONTROL_CHAR_RATIO &&
               (printableRatio + whitespaceRatio) > 0.6;
    }

    private boolean hasBOM(byte[] bytes) {
        if (bytes.length >= 3 &&
            bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]) {
            return true;
        }
        return bytes.length >= 2 &&
               ((bytes[0] == UTF16_BE_BOM[0] && bytes[1] == UTF16_BE_BOM[1]) ||
                (bytes[0] == UTF16_LE_BOM[0] && bytes[1] == UTF16_LE_BOM[1]));
    }

    private boolean hasBinarySignature(byte[] bytes) {
        if (bytes.length < 4) return false;

        int header = ((bytes[0] & 0xFF) << 24) |
                    ((bytes[1] & 0xFF) << 16) |
                    ((bytes[2] & 0xFF) << 8) |
                    (bytes[3] & 0xFF);

        return switch (header >>> 16) {
            case 0x4D5A -> true;
            case 0x504B -> true;
            case 0x5261 -> true;
            case 0x377A -> true;
            case 0x1F8B -> true;
            case 0x425A -> true;
            case 0xCAFE -> true;
            case 0x8950 -> true;
            case 0xFFD8 -> true;
            case 0x4749 -> true;
            case 0x424D -> true;
            case 0x2550 -> true;
            default -> false;
        };
    }

    private boolean isValidUTF8(byte[] bytes, int length) {
        int i = 0;
        while (i < length) {
            byte b = bytes[i];

            if ((b & 0x80) == 0) {
                i++;
            } else if ((b & 0xE0) == 0xC0) {
                if (i + 1 >= length || (bytes[i + 1] & 0xC0) != 0x80) {
                    return false;
                }
                i += 2;
            } else if ((b & 0xF0) == 0xE0) {
                if (i + 2 >= length ||
                    (bytes[i + 1] & 0xC0) != 0x80 ||
                    (bytes[i + 2] & 0xC0) != 0x80) {
                    return false;
                }
                i += 3;
            } else if ((b & 0xF8) == 0xF0) {
                if (i + 3 >= length ||
                    (bytes[i + 1] & 0xC0) != 0x80 ||
                    (bytes[i + 2] & 0xC0) != 0x80 ||
                    (bytes[i + 3] & 0xC0) != 0x80) {
                    return false;
                }
                i += 4;
            } else {
                return false;
            }
        }
        return true;
    }

    public boolean isArchiveFile(String filename) {
        String extension = getFileExtension(filename);
        return ARCHIVE_EXTENSIONS.contains(extension);
    }

    public boolean isSupportedFileType(String filename) {
        if (filename == null) return false;
        String extension = getFileExtension(filename);
        return ARCHIVE_EXTENSIONS.contains(extension)
                || TEXT_EXTENSIONS.contains(extension)
                || IMAGE_EXTENSIONS.contains(extension)
                || DOCUMENT_EXTENSIONS.contains(extension)
                || OTHER_EXTENSIONS.contains(extension);
    }

    public Set<String> getImageExtensions() {
        return IMAGE_EXTENSIONS;
    }

    public Set<String> getAllSupportedExtensions() {
        return Set.of(
            TEXT_EXTENSIONS, ARCHIVE_EXTENSIONS, IMAGE_EXTENSIONS,
            DOCUMENT_EXTENSIONS, OTHER_EXTENSIONS
        ).stream()
        .flatMap(Set::stream)
        .collect(java.util.stream.Collectors.toSet());
    }

    public void clearCaches() {
        textFileCache.clear();
        extensionCache.clear();
        logger.debug("FileTypeDetector caches cleared");
    }
}
