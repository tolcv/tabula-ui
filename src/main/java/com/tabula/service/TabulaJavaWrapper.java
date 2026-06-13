package com.tabula.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.annotation.PostConstruct;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.Rectangle;
import technology.tabula.Table;
import technology.tabula.TextElement;
import technology.tabula.detectors.NurminenDetectionAlgorithm;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;
import technology.tabula.writers.CSVWriter;
import technology.tabula.writers.TSVWriter;

/**
 * Wrapper around tabula-java library for PDF processing and table extraction.
 * Replaces Ruby's lib/tabula_java_wrapper.rb
 * 
 * Note: tabula-java is used for table detection/extraction in tabula-core library.
 * This wrapper provides PDF metadata extraction and image rendering using PDFBox.
 */
@Service
public class TabulaJavaWrapper {

    private static final Logger log = LoggerFactory.getLogger(TabulaJavaWrapper.class);

    @Value("${tabula.ocr.tessdata.path:}")
    private String tessdataPath;

    @Value("${tabula.ocr.language:eng}")
    private String ocrLanguage;

    @Value("${tabula.ocr.dpi:300}")
    private int ocrDpi;

    @Value("${tabula.ocr.psm:6}")
    private int ocrPsm;

    @Value("${tabula.ocr.oem:1}")
    private int ocrOem;

    @Value("${tabula.ocr.preserve-interword-spaces:true}")
    private boolean preserveInterwordSpaces;

    @Value("${tabula.ocr.char-whitelist:}")
    private String ocrCharWhitelist;

    @Value("${tabula.ocr.native-library-path:/opt/homebrew/lib}")
    private String ocrNativeLibraryPath;

    private volatile boolean ocrNativeUnavailable;

    @PostConstruct
    public void logOcrReadiness() {
        configureOcrNativePath();

        Path configuredTessdata = (tessdataPath == null || tessdataPath.isBlank())
            ? null
            : Path.of(tessdataPath.trim());
        boolean tessdataOk = configuredTessdata != null && Files.isDirectory(configuredTessdata);

        Path nativePath = (ocrNativeLibraryPath == null || ocrNativeLibraryPath.isBlank())
            ? null
            : Path.of(ocrNativeLibraryPath.trim());
        boolean nativePathOk = nativePath != null && Files.isDirectory(nativePath);

        try {
            ITesseract tesseract = new Tesseract();
            if (configuredTessdata != null) {
                tesseract.setDatapath(configuredTessdata.toString());
            }
            tesseract.setLanguage(ocrLanguage != null && !ocrLanguage.isBlank() ? ocrLanguage : "eng");
            tesseract.setPageSegMode(ocrPsm);
            tesseract.setOcrEngineMode(ocrOem);
            log.info("OCR ready: language={}, tessdataPath={} (exists={}), nativeLibraryPath={} (exists={})",
                ocrLanguage,
                configuredTessdata == null ? "<default>" : configuredTessdata,
                tessdataOk,
                nativePath == null ? "<default>" : nativePath,
                nativePathOk);
        } catch (UnsatisfiedLinkError e) {
            ocrNativeUnavailable = true;
            log.warn("OCR unavailable at startup: native Tesseract library not loadable. " +
                    "nativeLibraryPath={} (exists={}), tessdataPath={} (exists={}). Cause: {}",
                nativePath == null ? "<default>" : nativePath,
                nativePathOk,
                configuredTessdata == null ? "<default>" : configuredTessdata,
                tessdataOk,
                e.getMessage());
        } catch (Exception e) {
            log.warn("OCR startup check completed with warning: {}", e.getMessage());
        }
    }

    /**
     * Extract PDF metadata (page count, etc.).
     */
    public Map<String, Object> extractPdfMetadata(Path pdfPath) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        
        try (PDDocument document = PDDocument.load(new File(pdfPath.toString()))) {
            metadata.put("page_count", document.getNumberOfPages());
            metadata.put("encrypted", document.isEncrypted());
            
            // Extract PDF info if available
            if (document.getDocumentInformation() != null) {
                metadata.put("title", document.getDocumentInformation().getTitle());
                metadata.put("author", document.getDocumentInformation().getAuthor());
                metadata.put("subject", document.getDocumentInformation().getSubject());
                metadata.put("creator", document.getDocumentInformation().getCreator());
                metadata.put("producer", document.getDocumentInformation().getProducer());
                metadata.put("creation_date", document.getDocumentInformation().getCreationDate());
                metadata.put("modification_date", document.getDocumentInformation().getModificationDate());
            }
            
            log.info("Extracted metadata from PDF: {} pages", document.getNumberOfPages());
            return metadata;
        } catch (InvalidPasswordException e) {
            log.error("PDF is encrypted or password-protected", e);
            metadata.put("error", "PDF is encrypted or password-protected");
            return metadata;
        } catch (Exception e) {
            log.error("Failed to extract PDF metadata", e);
            metadata.put("error", "Failed to extract metadata: " + e.getMessage());
            return metadata;
        }
    }

    /**
     * Generate a thumbnail image for a specific page.
     * Uses PDFBox's rendering at specified DPI for thumbnail generation.
     */
    public BufferedImage generatePageThumbnail(Path pdfPath, int pageNumber, float scale) {
        try (PDDocument document = PDDocument.load(new File(pdfPath.toString()))) {
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
                throw new IllegalArgumentException("Invalid page number: " + pageNumber);
            }
            
            PDFRenderer renderer = new PDFRenderer(document);
            // Render at 150 DPI for thumbnails (provides good quality-size balance)
            int dpi = (int) (150 * scale);
            if (dpi < 50) dpi = 50;  // Minimum 50 DPI
            
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, dpi);
            
            log.debug("Generated thumbnail for page {} at {}x{} pixels", 
                     pageNumber, image.getWidth(), image.getHeight());
            return image;
        } catch (Exception e) {
            log.error("Failed to generate thumbnail for page {}", pageNumber, e);
            throw new RuntimeException("Failed to generate thumbnail", e);
        }
    }

    /**
     * Auto-detect tables on a specific page.
     * Returns a list of estimated table locations based on whitespace analysis.
     */
    public List<Map<String, Object>> detectTablesOnPage(Path pdfPath, int pageNumber) {
        List<Map<String, Object>> detectedTables = new ArrayList<>();

        try (PDDocument document = PDDocument.load(new File(pdfPath.toString()))) {
            ObjectExtractor extractor = new ObjectExtractor(document);
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
                log.warn("Page {} not found in PDF", pageNumber);
                return detectedTables;
            }

            Page page = extractor.extract(pageNumber);
            NurminenDetectionAlgorithm detector = new NurminenDetectionAlgorithm();
            List<Rectangle> rectangles = detector.detect(page);

            int idx = 0;
            for (Rectangle rect : rectangles) {
                Map<String, Object> tableInfo = new LinkedHashMap<>();
                tableInfo.put("index", idx++);
                tableInfo.put("top", rect.getTop());
                tableInfo.put("left", rect.getLeft());
                tableInfo.put("width", rect.getWidth());
                tableInfo.put("height", rect.getHeight());
                tableInfo.put("confidence", 1.0);
                detectedTables.add(tableInfo);
            }

            log.info("Detected {} potential tables on page {}", detectedTables.size(), pageNumber);
            try {
                extractor.close();
            } catch (IOException closeErr) {
                log.warn("Failed to close ObjectExtractor for page {}", pageNumber, closeErr);
            }
            return detectedTables;
        } catch (Exception e) {
            log.error("Failed to detect tables on page {}", pageNumber, e);
            // Return empty list instead of throwing - table detection is optional
            return new ArrayList<>();
        }
    }

    /**
     * Extract tables from a PDF page within specified rectangular areas.
     * Returns extracted data in the requested format.
     */
    public List<String> extractTablesFromPage(Path pdfPath, int pageNumber, 
                                              List<Map<String, Object>> selections, 
                                              String format, String extractionMethod) {
        List<String> results = new ArrayList<>();

        try (PDDocument document = PDDocument.load(new File(pdfPath.toString()))) {
            ObjectExtractor extractor = new ObjectExtractor(document);
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
                log.warn("Page {} not found in PDF", pageNumber);
                return results;
            }

            Page page = extractor.extract(pageNumber);
            log.info("Extracting tables from page {} ({} selections), format={}, method={}, pdf={}",
                pageNumber, selections.size(), format, extractionMethod, pdfPath.getFileName());

            for (int i = 0; i < selections.size(); i++) {
                Map<String, Object> selection = selections.get(i);

                try {
                    double x1 = getDouble(selection, "x1");
                    double y1 = getDouble(selection, "y1");
                    double x2 = getDouble(selection, "x2");
                    double y2 = getDouble(selection, "y2");
                    double width = getDouble(selection, "width");
                    double height = getDouble(selection, "height");
                    boolean invalidBox = (x2 <= x1) || (y2 <= y1);
                    log.info("Selection {} on page {}: x1={}, y1={}, x2={}, y2={}, width={}, height={}, invalidBox={}, raw={}",
                        i, pageNumber, x1, y1, x2, y2, width, height, invalidBox, selection);

                    if (invalidBox) {
                        log.warn("Skipping invalid selection {} on page {}", i, pageNumber);
                        results.add("");
                        continue;
                    }

                    float top = (float) Math.min(y1, y2);
                    float left = (float) Math.min(x1, x2);
                    float selWidth = (float) Math.abs(x2 - x1);
                    float selHeight = (float) Math.abs(y2 - y1);

                    Page area = page.getArea(new Rectangle(top, left, selWidth, selHeight));
                    List<Table> tables = extractTablesFromArea(area, extractionMethod);

                    List<TextElement> areaText = area.getText();
                    int textCount = areaText == null ? 0 : areaText.size();

                    if (textCount == 0) {
                        log.warn("Selection {} contains no text elements; attempting OCR (scanned/image-based page).", i);
                        String ocrResult = performOcrExtraction(pdfPath, pageNumber, top, left, selWidth, selHeight, format);
                        log.info("Selection {} OCR result: {} chars", i, ocrResult == null ? 0 : ocrResult.length());
                        results.add(ocrResult != null ? ocrResult : "");
                        continue;
                    }

                    String result = tablesToFormat(tables, format);
                    results.add(result);

                    int rowCount = tables.stream().mapToInt(Table::getRowCount).sum();
                    int colCount = tables.stream().mapToInt(Table::getColCount).sum();
                    log.info("Selection {} extracted: tableCount={}, rowCount={}, colCount={}, textCount={}, resultChars={}",
                        i, tables.size(), rowCount, colCount, textCount, result == null ? 0 : result.length());

                } catch (Exception e) {
                    log.error("Failed to extract table from selection {} on page {} with selection={}",
                        i, pageNumber, selection, e);
                    // Continue with next selection instead of failing completely
                    results.add("");
                }
            }

            log.info("Extracted {} results from page {} in {} format", results.size(), pageNumber, format);
            try {
                extractor.close();
            } catch (IOException closeErr) {
                log.warn("Failed to close ObjectExtractor after extraction on page {}", pageNumber, closeErr);
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to extract tables from page {}", pageNumber, e);
            // Return empty results instead of throwing
            return new ArrayList<>();
        }
    }

    private List<Table> extractTablesFromArea(Page area, String extractionMethod) {
        String method = extractionMethod == null ? "guess" : extractionMethod.toLowerCase(Locale.ROOT);
        SpreadsheetExtractionAlgorithm spreadsheet = new SpreadsheetExtractionAlgorithm();
        BasicExtractionAlgorithm basic = new BasicExtractionAlgorithm();

        if ("spreadsheet".equals(method) || "lattice".equals(method)) {
            List<Table> primary = spreadsheet.extract(area);
            if (hasNonEmptyRows(primary)) {
                return primary;
            }
            log.info("Spreadsheet extraction returned empty rows; falling back to basic extraction");
            return basic.extract(area);
        }
        if ("original".equals(method) || "stream".equals(method) || "basic".equals(method)) {
            List<Table> primary = basic.extract(area);
            if (hasNonEmptyRows(primary)) {
                return primary;
            }
            log.info("Basic extraction returned empty rows; falling back to spreadsheet extraction");
            return spreadsheet.extract(area);
        }

        // guess: prefer spreadsheet if region appears tabular, otherwise stream/basic
        if (spreadsheet.isTabular(area)) {
            List<Table> first = spreadsheet.extract(area);
            if (hasNonEmptyRows(first)) {
                return first;
            }
            log.info("Guess chose spreadsheet but rows were empty; retrying with basic extraction");
            return basic.extract(area);
        }
        List<Table> first = basic.extract(area);
        if (hasNonEmptyRows(first)) {
            return first;
        }
        log.info("Guess chose basic but rows were empty; retrying with spreadsheet extraction");
        return spreadsheet.extract(area);
    }

    private boolean hasNonEmptyRows(List<Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return false;
        }
        return tables.stream().anyMatch(t -> t.getRowCount() > 0 && t.getColCount() > 0);
    }

    private String tablesToFormat(List<Table> tables, String format) throws IOException {
        if (tables == null || tables.isEmpty()) {
            return "";
        }

        String normalized = format == null ? "csv" : format.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();

        if ("tsv".equals(normalized)) {
            TSVWriter writer = new TSVWriter();
            writer.write(out, tables);
            return out.toString();
        }

        if ("json".equals(normalized)) {
            // Controller expects CSV rows for preview parsing; keep CSV-like payload here.
            CSVWriter writer = new CSVWriter();
            writer.write(out, tables);
            return out.toString();
        }

        CSVWriter writer = new CSVWriter();
        writer.write(out, tables);
        return out.toString();
    }
    
    /**
     * Perform OCR extraction on a page region using Tesseract.
     * Called when the selected area contains no embedded text (scanned PDF).
     */
    private String performOcrExtraction(Path pdfPath, int pageNumber,
                                        float top, float left, float selWidth, float selHeight,
                                        String format) {
        if (ocrNativeUnavailable) {
            return "";
        }

        try (PDDocument document = PDDocument.load(pdfPath.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            float scale = ocrDpi / 72.0f;
            BufferedImage pageImage = renderer.renderImageWithDPI(pageNumber - 1, ocrDpi);

            int cropX = Math.max(0, Math.round(left * scale));
            int cropY = Math.max(0, Math.round(top * scale));
            int cropW = Math.min(Math.round(selWidth * scale), pageImage.getWidth() - cropX);
            int cropH = Math.min(Math.round(selHeight * scale), pageImage.getHeight() - cropY);

            if (cropW <= 0 || cropH <= 0) {
                log.warn("OCR crop area is empty (x={}, y={}, w={}, h={}); skipping", cropX, cropY, cropW, cropH);
                return "";
            }

            BufferedImage croppedImage = pageImage.getSubimage(cropX, cropY, cropW, cropH);
            BufferedImage preparedImage = preprocessImageForOcr(croppedImage);

            configureOcrNativePath();

            ITesseract tesseract = new Tesseract();
            if (tessdataPath != null && !tessdataPath.isBlank()) {
                tesseract.setDatapath(tessdataPath);
            }
            tesseract.setLanguage(ocrLanguage != null && !ocrLanguage.isBlank() ? ocrLanguage : "eng");
            tesseract.setPageSegMode(ocrPsm);
            tesseract.setOcrEngineMode(ocrOem);
            tesseract.setTessVariable("user_defined_dpi", Integer.toString(ocrDpi));
            if (preserveInterwordSpaces) {
                tesseract.setTessVariable("preserve_interword_spaces", "1");
            }
            if (ocrCharWhitelist != null && !ocrCharWhitelist.isBlank()) {
                tesseract.setTessVariable("tessedit_char_whitelist", ocrCharWhitelist);
            }

            String ocrText = tesseract.doOCR(preparedImage);
            if (ocrText == null || ocrText.isBlank()) {
                // Retry with a different segmentation mode for difficult table regions.
                tesseract.setPageSegMode(4);
                ocrText = tesseract.doOCR(preparedImage);
            }
            log.info("OCR succeeded on page {} region, text length={}", pageNumber, ocrText == null ? 0 : ocrText.length());
            return convertOcrTextToFormat(ocrText, format);

        } catch (TesseractException e) {
            log.error("Tesseract OCR failed on page {}: {}", pageNumber, e.getMessage());
            log.warn("Ensure Tesseract is installed (brew install tesseract) and tabula.ocr.tessdata.path is set if needed.");
            return "";
        } catch (UnsatisfiedLinkError e) {
            ocrNativeUnavailable = true;
            log.error("Tesseract native library could not be loaded; OCR disabled for this run: {}", e.getMessage());
            log.warn("Set tabula.ocr.native-library-path (for Homebrew usually /opt/homebrew/lib). Current value: {}", ocrNativeLibraryPath);
            return "";
        } catch (Exception e) {
            log.error("OCR extraction failed on page {}", pageNumber, e);
            return "";
        }
    }

    private BufferedImage preprocessImageForOcr(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();

        BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gGray = gray.createGraphics();
        gGray.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        gGray.drawImage(source, 0, 0, null);
        gGray.dispose();

        int threshold = calculateOtsuThreshold(gray);
        BufferedImage binary = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = gray.getRaster().getSample(x, y, 0);
                int value = pixel > threshold ? 0x00FFFFFF : 0x00000000;
                binary.setRGB(x, y, value);
            }
        }
        return binary;
    }

    private int calculateOtsuThreshold(BufferedImage gray) {
        int[] hist = new int[256];
        int width = gray.getWidth();
        int height = gray.getHeight();
        int total = width * height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = gray.getRaster().getSample(x, y, 0);
                hist[pixel]++;
            }
        }

        long sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += (long) i * hist[i];
        }

        long sumBackground = 0;
        int weightBackground = 0;
        double maxVariance = 0.0;
        int threshold = 127;

        for (int i = 0; i < 256; i++) {
            weightBackground += hist[i];
            if (weightBackground == 0) {
                continue;
            }

            int weightForeground = total - weightBackground;
            if (weightForeground == 0) {
                break;
            }

            sumBackground += (long) i * hist[i];
            double meanBackground = (double) sumBackground / weightBackground;
            double meanForeground = (double) (sum - sumBackground) / weightForeground;
            double varianceBetween = (double) weightBackground * weightForeground
                * (meanBackground - meanForeground) * (meanBackground - meanForeground);

            if (varianceBetween > maxVariance) {
                maxVariance = varianceBetween;
                threshold = i;
            }
        }

        return threshold;
    }

    private void configureOcrNativePath() {
        if (ocrNativeLibraryPath == null || ocrNativeLibraryPath.isBlank()) {
            return;
        }

        Path nativePath = Path.of(ocrNativeLibraryPath.trim());
        if (!Files.isDirectory(nativePath)) {
            return;
        }

        String existing = System.getProperty("jna.library.path", "");
        if (existing.contains(nativePath.toString())) {
            return;
        }

        String updated = existing.isBlank()
            ? nativePath.toString()
            : existing + File.pathSeparator + nativePath;
        System.setProperty("jna.library.path", updated);
    }

    /**
     * Convert raw OCR text into CSV or TSV rows.
     * Splits on newlines for rows, and 2+ consecutive spaces/tabs for cells.
     */
    private String convertOcrTextToFormat(String ocrText, String format) {
        if (ocrText == null || ocrText.isBlank()) {
            return "";
        }
        String normalized = format == null ? "csv" : format.toLowerCase(Locale.ROOT);
        boolean isTsv = "tsv".equals(normalized);
        String separator = isTsv ? "\t" : ",";

        String[] lines = ocrText.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Split cells by 2+ spaces or a tab character
            String[] cells = trimmed.split("  +|\t");
            StringJoiner joiner = new StringJoiner(separator);
            for (String cell : cells) {
                String c = cell.trim();
                if (isTsv) {
                    joiner.add(c);
                } else {
                    // Quote cells for CSV
                    joiner.add("\"" + c.replace("\"", "\"\"") + "\"");
                }
            }
            sb.append(joiner).append("\n");
        }
        return sb.toString();
    }

    /**
     * Format extraction result in the requested format.
     */
    private double getDouble(Map<String, Object> selection, String key) {
        Object value = selection.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Get page count, width, height, and rotation for all pages in a PDF.
     * Used by the frontend to render page images at the correct scale.
     */
    public List<Map<String, Object>> getPageDimensions(Path pdfPath) {
        List<Map<String, Object>> pages = new ArrayList<>();
        try (PDDocument document = PDDocument.load(new File(pdfPath.toString()))) {
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDPage page = document.getPage(i);
                PDRectangle mediaBox = page.getMediaBox();
                Map<String, Object> pageInfo = new LinkedHashMap<>();
                pageInfo.put("number", i + 1);
                pageInfo.put("width", mediaBox.getWidth());
                pageInfo.put("height", mediaBox.getHeight());
                pageInfo.put("rotation", page.getRotation());
                pages.add(pageInfo);
            }
        } catch (Exception e) {
            log.error("Failed to get page dimensions", e);
            throw new RuntimeException("Failed to get page dimensions", e);
        }
        return pages;
    }

    /**
     * Generate a thumbnail PNG for a specific page, returning raw bytes.
     * Results are cached to thumbnailCacheDir/{documentId}/page_{pageNumber}.png.
     */
    public byte[] generateThumbnailBytes(Path pdfPath, String documentId, int pageNumber,
                                         Path thumbnailCacheDir) throws IOException {
        Path docThumbDir = thumbnailCacheDir.resolve(documentId);
        Path thumbPath = docThumbDir.resolve("page_" + pageNumber + ".png");

        if (Files.exists(thumbPath)) {
            return Files.readAllBytes(thumbPath);
        }

        BufferedImage image = generatePageThumbnail(pdfPath, pageNumber, 0.5f);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        byte[] bytes = baos.toByteArray();

        Files.createDirectories(docThumbDir);
        Files.write(thumbPath, bytes);
        return bytes;
    }

    /**
     * Get the total number of pages in a PDF.
     */
    public int getPageCount(Path pdfPath) {
        try (PDDocument document = PDDocument.load(new File(pdfPath.toString()))) {
            return document.getNumberOfPages();
        } catch (Exception e) {
            log.error("Failed to get page count", e);
            throw new RuntimeException("Failed to get page count", e);
        }
    }

    /**
     * Extract all tables from all pages of a PDF.
     */
    public Map<Integer, List<String>> extractAllTables(Path pdfPath, String format) {
        Map<Integer, List<String>> allTables = new LinkedHashMap<>();

        try (PDDocument document = PDDocument.load(new File(pdfPath.toString()))) {
            ObjectExtractor extractor = new ObjectExtractor(document);
            int pageCount = document.getNumberOfPages();

            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                try {
                    List<String> pageResults = new ArrayList<>();

                    Page page = extractor.extract(pageNumber);
                    List<Table> tables = extractTablesFromArea(page, "guess");
                    String result = tablesToFormat(tables, format);
                    pageResults.add(result);

                    allTables.put(pageNumber, pageResults);
                } catch (Exception e) {
                    log.error("Failed to extract tables from page {}", pageNumber, e);
                }
            }

            try {
                extractor.close();
            } catch (IOException closeErr) {
                log.warn("Failed to close ObjectExtractor in extractAllTables", closeErr);
            }

            log.info("Extracted tables from {} pages", allTables.size());
            return allTables;
        } catch (Exception e) {
            log.error("Failed to extract all tables", e);
            // Return empty map instead of throwing
            return new LinkedHashMap<>();
        }
    }

}
