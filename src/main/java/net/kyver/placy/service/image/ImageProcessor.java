package net.kyver.placy.service.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ImageProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ImageProcessor.class);

    private final ConcurrentMap<String, byte[]> imageCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 200;

    private static final Set<String> METADATA_SUPPORTED_FORMATS = Set.of(
        "jpeg", "jpg", "png", "tiff", "tif", "gif", "bmp"
    );

    public byte[] processImage(byte[] imageBytes, String filename, Map<String, String> placeholders) throws IOException {
        if (placeholders.isEmpty()) {
            return imageBytes;
        }

        String cacheKey = createCacheKey(imageBytes, placeholders, filename);

        if (imageCache.size() > MAX_CACHE_SIZE) {
            imageCache.clear();
            logger.debug("Image cache cleared due to size limit");
        }

        return imageCache.computeIfAbsent(cacheKey, key -> {
            try {
                return processImageInternal(imageBytes, filename, placeholders);
            } catch (IOException e) {
                logger.warn("Failed to process image {}, returning original: {}", filename, e.getMessage());
                return imageBytes;
            }
        });
    }

    private byte[] processImageInternal(byte[] imageBytes, String filename, Map<String, String> placeholders) throws IOException {
        String format = getImageFormat(filename);

        if (!isMetadataEditingSupported(format)) {
            logger.debug("Metadata editing not supported for format: {}", format);
            return imageBytes;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
             ImageInputStream iis = ImageIO.createImageInputStream(bais)) {

            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                logger.debug("No image reader found for format: {}", format);
                return imageBytes;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);

                BufferedImage image = reader.read(0);
                IIOMetadata metadata = reader.getImageMetadata(0);

                if (metadata != null) {
                    processMetadata(metadata, placeholders);
                }

                return writeImageWithMetadata(image, metadata, format);
            } finally {
                reader.dispose();
            }

        } catch (Exception e) {
            logger.warn("Error processing image metadata for {}: {}", filename, e.getMessage());
            return imageBytes;
        }
    }

    private void processMetadata(IIOMetadata metadata, Map<String, String> placeholders) {
        try {
            String[] metadataFormatNames = metadata.getMetadataFormatNames();

            for (String formatName : metadataFormatNames) {
                try {
                    IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(formatName);
                    processMetadataNode(root, placeholders);
                    metadata.setFromTree(formatName, root);
                } catch (Exception e) {
                    logger.debug("Failed to process metadata format {}: {}", formatName, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to process metadata: {}", e.getMessage());
        }
    }

    private void processMetadataNode(IIOMetadataNode node, Map<String, String> placeholders) {
        String textContent = node.getTextContent();
        if (textContent != null && !textContent.trim().isEmpty()) {
            String processedContent = applyPlaceholders(textContent, placeholders);
            if (!textContent.equals(processedContent)) {
                node.setTextContent(processedContent);
            }
        }

        if (node.hasAttributes()) {
            for (int i = 0; i < node.getAttributes().getLength(); i++) {
                org.w3c.dom.Node attr = node.getAttributes().item(i);
                String value = attr.getNodeValue();
                if (value != null) {
                    String processedValue = applyPlaceholders(value, placeholders);
                    if (!value.equals(processedValue)) {
                        attr.setNodeValue(processedValue);
                    }
                }
            }
        }

        for (int i = 0; i < node.getLength(); i++) {
            org.w3c.dom.Node child = node.item(i);
            if (child instanceof IIOMetadataNode childNode) {
                processMetadataNode(childNode, placeholders);
            }
        }
    }

    private byte[] writeImageWithMetadata(BufferedImage image, IIOMetadata metadata, String format) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
            if (!writers.hasNext()) {
                throw new IOException("No writer available for format: " + format);
            }

            javax.imageio.ImageWriter writer = writers.next();
            try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);

                javax.imageio.ImageWriteParam writeParam = writer.getDefaultWriteParam();

                if (writeParam.canWriteCompressed()) {
                    writeParam.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                    String[] compressionTypes = writeParam.getCompressionTypes();
                    if (compressionTypes != null && compressionTypes.length > 0) {
                        writeParam.setCompressionType(compressionTypes[0]);
                        writeParam.setCompressionQuality(0.9f);
                    }
                }

                javax.imageio.IIOImage iioImage = new javax.imageio.IIOImage(image, null, metadata);
                writer.write(null, iioImage, writeParam);

                return baos.toByteArray();
            } finally {
                writer.dispose();
            }
        }
    }

    private String getImageFormat(String filename) {
        if (filename == null) return "";

        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            String ext = filename.substring(lastDot + 1).toLowerCase();
            return "jpg".equals(ext) ? "jpeg" : ext;
        }
        return "";
    }

    private boolean isMetadataEditingSupported(String format) {
        return METADATA_SUPPORTED_FORMATS.contains(format);
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders.isEmpty()) {
            return text;
        }

        final String[] result = {text};
        placeholders.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getKey().length(), e1.getKey().length()))
                .forEach(entry -> {
                    if (result[0].contains(entry.getKey())) {
                        result[0] = result[0].replace(entry.getKey(), entry.getValue());
                    }
                });
        return result[0];
    }

    private String createCacheKey(byte[] imageBytes, Map<String, String> placeholders, String filename) {
        return filename + ":" + imageBytes.length + ":" + placeholders.hashCode();
    }
}
