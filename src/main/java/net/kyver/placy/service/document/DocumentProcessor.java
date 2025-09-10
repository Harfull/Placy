package net.kyver.placy.service.document;

import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class DocumentProcessor {
    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessor.class);

    private final ConcurrentMap<String, byte[]> documentCache = new ConcurrentHashMap<>();

    public byte[] processDocument(byte[] documentBytes, String filename, Map<String, String> placeholders) {
        if (placeholders.isEmpty()) {
            return documentBytes;
        }

        String cacheKey = createCacheKey(documentBytes, placeholders, filename);
        return documentCache.computeIfAbsent(cacheKey, key -> {
            try {
                return processDocumentInternal(documentBytes, filename, placeholders);
            } catch (Exception e) {
                logger.warn("Failed to process document {}, returning original", filename, e);
                return documentBytes;
            }
        });
    }

    private byte[] processDocumentInternal(byte[] documentBytes, String filename, Map<String, String> placeholders) throws IOException {
        String extension = getFileExtension(filename).toLowerCase();

        return switch (extension) {
            case ".docx" -> processWordDocument(documentBytes, placeholders);
            case ".doc" -> processLegacyWordDocument(documentBytes, placeholders);
            case ".xlsx" -> processExcelDocument(documentBytes, placeholders);
            case ".xls" -> processLegacyExcelDocument(documentBytes, placeholders);
            case ".pptx" -> processPowerPointDocument(documentBytes, placeholders);
            case ".pdf" -> processPdfDocument(documentBytes);
            case ".txt", ".md", ".json", ".xml", ".html", ".css", ".js", ".properties", ".yml", ".yaml" ->
                processTextDocument(documentBytes, placeholders);
            default -> {
                logger.debug("Unsupported document format: {}", extension);
                yield documentBytes;
            }
        };
    }

    private byte[] processWordDocument(byte[] documentBytes, Map<String, String> placeholders) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(documentBytes);
             XWPFDocument document = new XWPFDocument(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            try {
                POIXMLProperties properties = document.getProperties();
                if (properties != null && properties.getCoreProperties() != null) {
                    POIXMLProperties.CoreProperties core = properties.getCoreProperties();

                    if (core.getTitle() != null) {
                        core.setTitle(applyPlaceholders(core.getTitle(), placeholders));
                    }
                    if (core.getDescription() != null) {
                        core.setDescription(applyPlaceholders(core.getDescription(), placeholders));
                    }
                    if (core.getCreator() != null) {
                        core.setCreator(applyPlaceholders(core.getCreator(), placeholders));
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not process document properties: {}", e.getMessage());
            }

            document.getParagraphs().forEach(paragraph ->
                paragraph.getRuns().forEach(run -> {
                    String text = run.getText(0);
                    if (text != null) {
                        String processedText = applyPlaceholders(text, placeholders);
                        if (!text.equals(processedText)) {
                            run.setText(processedText, 0);
                        }
                    }
                })
            );

            document.getHeaderList().forEach(header ->
                header.getParagraphs().forEach(paragraph ->
                    paragraph.getRuns().forEach(run -> {
                        String text = run.getText(0);
                        if (text != null) {
                            String processedText = applyPlaceholders(text, placeholders);
                            if (!text.equals(processedText)) {
                                run.setText(processedText, 0);
                            }
                        }
                    })
                )
            );

            document.write(baos);
            return baos.toByteArray();
        }
    }

    private byte[] processLegacyWordDocument(byte[] documentBytes, Map<String, String> placeholders) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(documentBytes);
             HWPFDocument document = new HWPFDocument(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            SummaryInformation summary = document.getSummaryInformation();
            if (summary != null) {
                if (summary.getTitle() != null) {
                    summary.setTitle(applyPlaceholders(summary.getTitle(), placeholders));
                }
                if (summary.getSubject() != null) {
                    summary.setSubject(applyPlaceholders(summary.getSubject(), placeholders));
                }
                if (summary.getAuthor() != null) {
                    summary.setAuthor(applyPlaceholders(summary.getAuthor(), placeholders));
                }
            }

            String text = document.getDocumentText();
            String processedText = applyPlaceholders(text, placeholders);
            if (!text.equals(processedText)) {
                document.getRange().replaceText(text, processedText);
            }

            document.write(baos);
            return baos.toByteArray();
        }
    }

    private byte[] processExcelDocument(byte[] documentBytes, Map<String, String> placeholders) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(documentBytes);
             XSSFWorkbook workbook = new XSSFWorkbook(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            try {
                POIXMLProperties properties = workbook.getProperties();
                if (properties != null && properties.getCoreProperties() != null) {
                    POIXMLProperties.CoreProperties core = properties.getCoreProperties();

                    if (core.getTitle() != null) {
                        core.setTitle(applyPlaceholders(core.getTitle(), placeholders));
                    }
                    if (core.getDescription() != null) {
                        core.setDescription(applyPlaceholders(core.getDescription(), placeholders));
                    }
                    if (core.getCreator() != null) {
                        core.setCreator(applyPlaceholders(core.getCreator(), placeholders));
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not process workbook properties: {}", e.getMessage());
            }

            workbook.forEach(sheet ->
                sheet.forEach(row ->
                    row.forEach(cell -> {
                        if (cell.getCellType() == CellType.STRING) {
                            String cellValue = cell.getStringCellValue();
                            String processedValue = applyPlaceholders(cellValue, placeholders);
                            if (!cellValue.equals(processedValue)) {
                                cell.setCellValue(processedValue);
                            }
                        }
                    })
                )
            );

            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    private byte[] processLegacyExcelDocument(byte[] documentBytes, Map<String, String> placeholders) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(documentBytes);
             HSSFWorkbook workbook = new HSSFWorkbook(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            SummaryInformation summary = workbook.getSummaryInformation();
            if (summary != null) {
                if (summary.getTitle() != null) {
                    summary.setTitle(applyPlaceholders(summary.getTitle(), placeholders));
                }
                if (summary.getSubject() != null) {
                    summary.setSubject(applyPlaceholders(summary.getSubject(), placeholders));
                }
                if (summary.getAuthor() != null) {
                    summary.setAuthor(applyPlaceholders(summary.getAuthor(), placeholders));
                }
            }

            workbook.forEach(sheet ->
                sheet.forEach(row ->
                    row.forEach(cell -> {
                        if (cell.getCellType() == CellType.STRING) {
                            String cellValue = cell.getStringCellValue();
                            String processedValue = applyPlaceholders(cellValue, placeholders);
                            if (!cellValue.equals(processedValue)) {
                                cell.setCellValue(processedValue);
                            }
                        }
                    })
                )
            );

            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    private byte[] processPowerPointDocument(byte[] documentBytes, Map<String, String> placeholders) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(documentBytes);
             XMLSlideShow ppt = new XMLSlideShow(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            try {
                POIXMLProperties properties = ppt.getProperties();
                if (properties != null && properties.getCoreProperties() != null) {
                    POIXMLProperties.CoreProperties core = properties.getCoreProperties();

                    if (core.getTitle() != null) {
                        core.setTitle(applyPlaceholders(core.getTitle(), placeholders));
                    }
                    if (core.getDescription() != null) {
                        core.setDescription(applyPlaceholders(core.getDescription(), placeholders));
                    }
                    if (core.getCreator() != null) {
                        core.setCreator(applyPlaceholders(core.getCreator(), placeholders));
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not process presentation properties: {}", e.getMessage());
            }

            ppt.getSlides().forEach(slide ->
                slide.getShapes().forEach(shape -> {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null) {
                            String processedText = applyPlaceholders(text, placeholders);
                            if (!text.equals(processedText)) {
                                textShape.setText(processedText);
                            }
                        }
                    }
                })
            );

            ppt.write(baos);
            return baos.toByteArray();
        }
    }

    private byte[] processPdfDocument(byte[] documentBytes) {
        logger.debug("PDF processing not implemented yet");
        return documentBytes;
    }

    private byte[] processTextDocument(byte[] documentBytes, Map<String, String> placeholders) {
        String content = new String(documentBytes, java.nio.charset.StandardCharsets.UTF_8);
        String processedContent = applyPlaceholders(content, placeholders);
        return processedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

    private String getFileExtension(String filename) {
        if (filename == null) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot > 0 ? filename.substring(lastDot).toLowerCase() : "";
    }

    private String createCacheKey(byte[] documentBytes, Map<String, String> placeholders, String filename) {
        return filename + ":" + documentBytes.length + ":" + placeholders.hashCode();
    }

    public boolean isSupportedDocument(String filename) {
        String extension = getFileExtension(filename).toLowerCase();
        return switch (extension) {
            case ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".pdf",
                 ".txt", ".md", ".json", ".xml", ".html", ".css",
                 ".js", ".properties", ".yml", ".yaml" -> true;
            default -> false;
        };
    }

    public void clearCache() {
        documentCache.clear();
        logger.debug("Document cache cleared");
    }

    public int getCacheSize() {
        return documentCache.size();
    }
}
