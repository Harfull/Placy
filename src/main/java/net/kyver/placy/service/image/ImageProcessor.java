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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ImageProcessor {
    private static final Logger logger = LoggerFactory.getLogger(ImageProcessor.class);

    private final ConcurrentMap<String, byte[]> imageCache = new ConcurrentHashMap<>();

    private static final String[] METADATA_SUPPORTED_FORMATS = {"jpeg", "jpg", "png", "tiff", "tif", "gif"};

    public byte[] processImage(byte[] imageBytes, String filename, Map<String, String> placeholders) throws IOException {
        if (placeholders.isEmpty()) {
            return imageBytes;
        }

        String cacheKey = createCacheKey(imageBytes, placeholders, filename);
        return imageCache.computeIfAbsent(cacheKey, key -> {
            try {
                return processImageInternal(imageBytes, filename, placeholders);
            } catch (IOException e) {
                logger.warn("Failed to process image {}, returning original", filename, e);
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
            reader.setInput(iis);

            BufferedImage image = reader.read(0);
            IIOMetadata metadata = reader.getImageMetadata(0);

            if (metadata != null) {
                processMetadata(metadata, placeholders);
            }

            return writeImageWithMetadata(image, metadata, format);

        } catch (Exception e) {
            logger.warn("Error processing image metadata for {}: {}", filename, e.getMessage());
            return imageBytes;
        }
    }

    private void processMetadata(IIOMetadata metadata, Map<String, String> placeholders) {
        try {
            String[] metadataFormatNames = metadata.getMetadataFormatNames();

            for (String formatName : metadataFormatNames) {
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(formatName);
                processMetadataNode(root, placeholders);
                metadata.setFromTree(formatName, root);
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
            if (child instanceof IIOMetadataNode) {
                processMetadataNode((IIOMetadataNode) child, placeholders);
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
                javax.imageio.IIOImage iioImage = new javax.imageio.IIOImage(image, null, metadata);

                writer.write(null, iioImage, writeParam);
                writer.dispose();

                return baos.toByteArray();
            }
        }
    }

    private String getImageFormat(String filename) {
        if (filename == null) return "";

        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            String ext = filename.substring(lastDot + 1).toLowerCase();
            if ("jpg".equals(ext)) {
                return "jpeg";
            }
            return ext;
        }
        return "";
    }

    private boolean isMetadataEditingSupported(String format) {
        for (String supportedFormat : METADATA_SUPPORTED_FORMATS) {
            if (supportedFormat.equals(format)) {
                return true;
            }
        }
        return false;
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders.isEmpty()) {
            return text;
        }

        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private String createCacheKey(byte[] imageBytes, Map<String, String> placeholders, String filename) {
        return filename + ":" + imageBytes.length + ":" + placeholders.hashCode();
    }

    public boolean supportsMetadataEditing(String filename) {
        String format = getImageFormat(filename);
        return isMetadataEditingSupported(format);
    }

    public ImageInfo getImageInfo(byte[] imageBytes, String filename) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
             ImageInputStream iis = ImageIO.createImageInputStream(bais)) {

            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("No image reader found for: " + filename);
            }

            ImageReader reader = readers.next();
            reader.setInput(iis);

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            String format = reader.getFormatName();

            reader.dispose();

            return new ImageInfo(width, height, format, supportsMetadataEditing(filename));
        }
    }

    public void clearCache() {
        imageCache.clear();
        logger.debug("Image cache cleared");
    }

    public int getCacheSize() {
        return imageCache.size();
    }

    public static record ImageInfo(int width, int height, String format, boolean supportsMetadataEditing) {}
}
