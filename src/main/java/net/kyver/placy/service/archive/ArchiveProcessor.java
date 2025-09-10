package net.kyver.placy.service.archive;

import net.kyver.placy.service.file.FileTypeDetector;
import net.kyver.placy.service.file.StreamProcessor;
import net.kyver.placy.service.image.ImageProcessor;
import net.kyver.placy.service.document.DocumentProcessor;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorOutputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Component
public class ArchiveProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ArchiveProcessor.class);

    private final FileTypeDetector fileTypeDetector;
    private final StreamProcessor streamProcessor;
    private final ClassProcessor classProcessor;
    private final ImageProcessor imageProcessor;
    private final DocumentProcessor documentProcessor;

    private final ConcurrentMap<String, Long> crcCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, byte[]> archiveCache = new ConcurrentHashMap<>();

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
        if (placeholders.isEmpty()) {
            return archiveBytes;
        }

        String cacheKey = createCacheKey(archiveBytes, placeholders, filename);
        return archiveCache.computeIfAbsent(cacheKey, key -> {
            try {
                return processArchiveInternal(archiveBytes, filename, placeholders);
            } catch (Exception e) {
                logger.warn("Failed to process archive {}, returning original", filename, e);
                return archiveBytes;
            }
        });
    }

    private byte[] processArchiveInternal(byte[] archiveBytes, String filename, Map<String, String> placeholders) throws IOException {
        String extension = fileTypeDetector.getFileExtension(filename);

        return switch (extension) {
            case ".jar", ".war", ".ear", ".aar" -> transformJarFile(archiveBytes, placeholders);
            case ".zip", ".apk", ".xpi", ".crx", ".vsix", ".nupkg", ".snupkg" ->
                transformZipFile(archiveBytes, placeholders);
            case ".tar" -> transformTarFile(archiveBytes, placeholders);
            case ".tar.gz", ".tgz" -> transformCompressedTarFile(archiveBytes, placeholders, "gzip");
            case ".tar.bz2", ".tbz2" -> transformCompressedTarFile(archiveBytes, placeholders, "bzip2");
            case ".tar.xz", ".txz" -> transformCompressedTarFile(archiveBytes, placeholders, "xz");
            case ".7z", ".7zip" -> transform7zFile(archiveBytes, placeholders);
            case ".gz", ".gzip" -> transformGzipFile(archiveBytes, placeholders);
            case ".bz2", ".bzip2" -> transformBzip2File(archiveBytes, placeholders);
            case ".xz" -> transformXzFile(archiveBytes, placeholders);
            default -> {
                logger.debug("Unsupported archive format: {}", extension);
                yield archiveBytes;
            }
        };
    }

    private byte[] transformJarFile(byte[] jarBytes, Map<String, String> placeholders) throws IOException {
        logger.debug("Transforming JAR file of size: {} bytes", jarBytes.length);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(jarBytes);
             JarInputStream jis = new JarInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream(jarBytes.length)) {

            Manifest manifest = jis.getManifest();

            try (JarOutputStream jos = manifest != null ?
                new JarOutputStream(baos, manifest) : new JarOutputStream(baos)) {

                processJarEntries(jis, jos, placeholders, jarBytes);

                jos.finish();
                logger.debug("Successfully transformed JAR file with {} placeholders", placeholders.size());
                return baos.toByteArray();
            }
        }
    }

    private void processJarEntries(JarInputStream jis, JarOutputStream jos,
                                 Map<String, String> placeholders, byte[] originalJarBytes) throws IOException {

        Map<String, EntryData> entries = new LinkedHashMap<>();
        JarEntry entry;

        while ((entry = jis.getNextJarEntry()) != null) {
            if (entry.isDirectory()) {
                entries.put(entry.getName(), new EntryData(entry, null, true));
                continue;
            }

            byte[] entryBytes = streamProcessor.readAllBytes(jis);
            entries.put(entry.getName(), new EntryData(entry, entryBytes, false));
        }

        try (JarInputStream originalJis = new JarInputStream(new ByteArrayInputStream(originalJarBytes))) {
            JarEntry originalEntry;
            while ((originalEntry = originalJis.getNextJarEntry()) != null) {
                EntryData entryData = entries.get(originalEntry.getName());
                if (entryData == null) continue;

                if (entryData.isDirectory) {
                    writeDirectoryEntry(jos, originalEntry);
                } else {
                    writeFileEntry(jos, originalEntry, entryData.data, placeholders);
                }
            }
        }
    }

    private void writeDirectoryEntry(JarOutputStream jos, JarEntry originalEntry) throws IOException {
        JarEntry dirEntry = new JarEntry(originalEntry.getName());
        copyEntryAttributes(originalEntry, dirEntry);
        jos.putNextEntry(dirEntry);
        jos.closeEntry();
    }

    private void writeFileEntry(JarOutputStream jos, JarEntry originalEntry,
                              byte[] originalBytes, Map<String, String> placeholders) throws IOException {
        String entryName = originalEntry.getName();
        byte[] transformedBytes = processEntryContent(entryName, originalBytes, placeholders);

        JarEntry newEntry = new JarEntry(entryName);
        copyEntryAttributes(originalEntry, newEntry);

        newEntry.setSize(transformedBytes.length);
        if (newEntry.getMethod() == ZipEntry.STORED) {
            newEntry.setCrc(calculateCRC32Cached(entryName + transformedBytes.length, transformedBytes));
        }

        jos.putNextEntry(newEntry);
        jos.write(transformedBytes);
        jos.closeEntry();
    }

    private byte[] transformZipFile(byte[] zipBytes, Map<String, String> placeholders) throws IOException {
        logger.debug("Transforming ZIP file of size: {} bytes", zipBytes.length);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(zipBytes);
             ByteArrayOutputStream baos = new ByteArrayOutputStream(zipBytes.length);
             ZipInputStream zis = new ZipInputStream(bais);
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    ZipEntry dirEntry = new ZipEntry(entry.getName());
                    dirEntry.setTime(entry.getTime());
                    zos.putNextEntry(dirEntry);
                    zos.closeEntry();
                    continue;
                }

                byte[] entryBytes = streamProcessor.readAllBytes(zis);
                byte[] transformedBytes = processEntryContent(entry.getName(), entryBytes, placeholders);

                ZipEntry newEntry = new ZipEntry(entry.getName());
                newEntry.setTime(entry.getTime());
                zos.putNextEntry(newEntry);
                zos.write(transformedBytes);
                zos.closeEntry();
            }

            logger.debug("Transformed ZIP file with {} placeholders", placeholders.size());
            return baos.toByteArray();
        }
    }

    private byte[] transformTarFile(byte[] tarBytes, Map<String, String> placeholders) throws IOException {
        logger.debug("Processing TAR file");

        try (ByteArrayInputStream bais = new ByteArrayInputStream(tarBytes);
             TarArchiveInputStream tais = new TarArchiveInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream();
             TarArchiveOutputStream taos = new TarArchiveOutputStream(baos)) {

            TarArchiveEntry entry;
            while ((entry = tais.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    taos.putArchiveEntry(entry);
                    taos.closeArchiveEntry();
                    continue;
                }

                byte[] entryBytes = streamProcessor.readAllBytes(tais);
                byte[] transformedBytes = processEntryContent(entry.getName(), entryBytes, placeholders);

                TarArchiveEntry newEntry = new TarArchiveEntry(entry.getName());
                newEntry.setSize(transformedBytes.length);
                newEntry.setModTime(entry.getModTime());
                newEntry.setMode(entry.getMode());

                taos.putArchiveEntry(newEntry);
                taos.write(transformedBytes);
                taos.closeArchiveEntry();
            }

            taos.finish();
            return baos.toByteArray();
        }
    }

    private byte[] transformCompressedTarFile(byte[] archiveBytes, Map<String, String> placeholders, String compressionType) throws IOException {
        logger.debug("Processing compressed TAR file with {} compression", compressionType);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(archiveBytes)) {

            CompressorInputStream cis = null;
            try {
                cis = new CompressorStreamFactory().createCompressorInputStream(compressionType, bais);
            } catch (org.apache.commons.compress.compressors.CompressorException e) {
                throw new IOException("Failed to create compressor input stream for " + compressionType, e);
            }

            try (CompressorInputStream compressorStream = cis;
                 ByteArrayOutputStream tarBaos = new ByteArrayOutputStream()) {

                streamProcessor.copyStream(compressorStream, tarBaos);
                byte[] tarBytes = tarBaos.toByteArray();

                byte[] processedTarBytes = transformTarFile(tarBytes, placeholders);

                try (ByteArrayOutputStream compressedBaos = new ByteArrayOutputStream()) {

                    CompressorOutputStream cos = null;
                    try {
                        cos = new CompressorStreamFactory().createCompressorOutputStream(compressionType, compressedBaos);
                    } catch (org.apache.commons.compress.compressors.CompressorException e) {
                        throw new IOException("Failed to create compressor output stream for " + compressionType, e);
                    }

                    try (CompressorOutputStream compressorOut = cos;
                         ByteArrayInputStream processedBais = new ByteArrayInputStream(processedTarBytes)) {

                        streamProcessor.copyStream(processedBais, compressorOut);
                        compressorOut.close();
                        return compressedBaos.toByteArray();
                    }
                }
            }
        }
    }

    private byte[] transform7zFile(byte[] sevenZBytes, Map<String, String> placeholders) throws IOException {
        logger.debug("Processing 7Z file");

        Path inputTemp = Files.createTempFile("input", ".7z");
        Path outputTemp = Files.createTempFile("output", ".7z");

        try {
            Files.write(inputTemp, sevenZBytes);

            try (SevenZFile sevenZFile = new SevenZFile(inputTemp.toFile());
                 SevenZOutputFile output = new SevenZOutputFile(outputTemp.toFile())) {

                SevenZArchiveEntry entry;
                while ((entry = sevenZFile.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        output.putArchiveEntry(entry);
                        output.closeArchiveEntry();
                        continue;
                    }

                    byte[] entryBytes = new byte[(int) entry.getSize()];
                    sevenZFile.read(entryBytes);

                    byte[] transformedBytes = processEntryContent(entry.getName(), entryBytes, placeholders);

                    SevenZArchiveEntry newEntry = output.createArchiveEntry(
                        outputTemp.toFile(), entry.getName());
                    newEntry.setSize(transformedBytes.length);

                    output.putArchiveEntry(newEntry);
                    output.write(transformedBytes);
                    output.closeArchiveEntry();
                }

                output.finish();
            }

            return Files.readAllBytes(outputTemp);

        } finally {
            Files.deleteIfExists(inputTemp);
            Files.deleteIfExists(outputTemp);
        }
    }

    private byte[] transformGzipFile(byte[] gzipBytes, Map<String, String> placeholders) throws IOException {
        return transformCompressedFile(gzipBytes, placeholders, "gzip");
    }

    private byte[] transformBzip2File(byte[] bzip2Bytes, Map<String, String> placeholders) throws IOException {
        return transformCompressedFile(bzip2Bytes, placeholders, "bzip2");
    }

    private byte[] transformXzFile(byte[] xzBytes, Map<String, String> placeholders) throws IOException {
        return transformCompressedFile(xzBytes, placeholders, "xz");
    }

    private byte[] transformCompressedFile(byte[] compressedBytes, Map<String, String> placeholders, String compressionType) throws IOException {
        logger.debug("Processing {} compressed file", compressionType);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedBytes)) {

            CompressorInputStream cis = null;
            try {
                cis = new CompressorStreamFactory().createCompressorInputStream(compressionType, bais);
            } catch (org.apache.commons.compress.compressors.CompressorException e) {
                throw new IOException("Failed to create compressor input stream for " + compressionType, e);
            }

            try (CompressorInputStream compressorStream = cis;
                 ByteArrayOutputStream decompressedBaos = new ByteArrayOutputStream()) {

                streamProcessor.copyStream(compressorStream, decompressedBaos);
                byte[] decompressedBytes = decompressedBaos.toByteArray();

                if (fileTypeDetector.isTextFile("", decompressedBytes)) {
                    decompressedBytes = streamProcessor.transformTextStream(decompressedBytes, placeholders);
                }

                try (ByteArrayOutputStream compressedBaos = new ByteArrayOutputStream()) {

                    CompressorOutputStream cos = null;
                    try {
                        cos = new CompressorStreamFactory().createCompressorOutputStream(compressionType, compressedBaos);
                    } catch (org.apache.commons.compress.compressors.CompressorException e) {
                        throw new IOException("Failed to create compressor output stream for " + compressionType, e);
                    }

                    try (CompressorOutputStream compressorOut = cos;
                         ByteArrayInputStream processedBais = new ByteArrayInputStream(decompressedBytes)) {

                        streamProcessor.copyStream(processedBais, compressorOut);
                        compressorOut.close();
                        return compressedBaos.toByteArray();
                    }
                }
            }
        }
    }

    private byte[] processEntryContent(String entryName, byte[] originalBytes, Map<String, String> placeholders) {
        try {
            if (entryName.endsWith(".class")) {
                return classProcessor.replacePlaceholdersInClass(originalBytes, placeholders);
            } else if (fileTypeDetector.isTextFile(entryName, originalBytes)) {
                return streamProcessor.transformTextStream(originalBytes, placeholders);
            } else if (isImageFile(entryName)) {
                return imageProcessor.processImage(originalBytes, entryName, placeholders);
            } else if (documentProcessor.isSupportedDocument(entryName)) {
                return documentProcessor.processDocument(originalBytes, entryName, placeholders);
            } else {
                return originalBytes;
            }
        } catch (Exception e) {
            logger.warn("Failed to process entry {}: {}", entryName, e.getMessage());
            return originalBytes;
        }
    }

    private boolean isImageFile(String filename) {
        String extension = fileTypeDetector.getFileExtension(filename);
        return fileTypeDetector.getImageExtensions().contains(extension);
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

    private long calculateCRC32Cached(String cacheKey, byte[] data) {
        return crcCache.computeIfAbsent(cacheKey, key -> {
            CRC32 crc = new CRC32();
            crc.update(data);
            return crc.getValue();
        });
    }

    private String createCacheKey(byte[] archiveBytes, Map<String, String> placeholders, String filename) {
        return filename + ":" + archiveBytes.length + ":" + placeholders.hashCode();
    }

    public void clearCache() {
        crcCache.clear();
        archiveCache.clear();
        logger.debug("Archive processor caches cleared");
    }

    public Set<String> getSupportedFormats() {
        return Set.of(".jar", ".war", ".ear", ".aar", ".zip", ".apk", ".xpi", ".crx", ".vsix",
                     ".nupkg", ".snupkg", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2",
                     ".tar.xz", ".txz", ".7z", ".7zip", ".gz", ".gzip", ".bz2", ".bzip2", ".xz");
    }

    private static class EntryData {
        final JarEntry entry;
        final byte[] data;
        final boolean isDirectory;

        EntryData(JarEntry entry, byte[] data, boolean isDirectory) {
            this.entry = entry;
            this.data = data;
            this.isDirectory = isDirectory;
        }
    }
}
