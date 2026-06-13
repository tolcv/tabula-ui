package com.tabula.controller;

import com.google.gson.Gson;
import com.tabula.model.Document;
import com.tabula.model.Job;
import com.tabula.service.JobExecutorService;
import com.tabula.service.TabulaJavaWrapper;
import com.tabula.service.WorkspaceService;
import com.tabula.config.ApplicationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API endpoints for document management.
 */
@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "*")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final WorkspaceService workspaceService;
    private final TabulaJavaWrapper tabulaWrapper;
    private final JobExecutorService jobExecutorService;
    private final ApplicationConfiguration config;
    private final Gson gson;

    public DocumentController(WorkspaceService workspaceService,
                             TabulaJavaWrapper tabulaWrapper,
                             JobExecutorService jobExecutorService,
                             ApplicationConfiguration config,
                             Gson gson) {
        this.workspaceService = workspaceService;
        this.tabulaWrapper = tabulaWrapper;
        this.jobExecutorService = jobExecutorService;
        this.config = config;
        this.gson = gson;
    }

    /**
     * GET /api/documents - Get all documents.
     */
    @GetMapping
    public ResponseEntity<List<Document>> getAllDocuments() {
        List<Document> documents = workspaceService.getAllDocuments();
        return ResponseEntity.ok(documents);
    }

    /**
     * GET /api/documents/{id} - Get a specific document.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getDocument(@PathVariable String id) {
        Optional<Document> document = workspaceService.getDocument(id);
        
        if (document.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Document not found"));
        }
        
        return ResponseEntity.ok(document.get());
    }

    /**
     * POST /api/documents/upload - Upload one or more PDF documents.
     * Accepts multipart field name "files[]" (multi-file) or "file" (single).
     * Returns [{filename, success, file_id}] for each uploaded file.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadDocuments(
            @RequestParam(value = "files[]", required = false) List<MultipartFile> filesParam,
            @RequestParam(value = "file", required = false) MultipartFile singleFile) {
        try {
            List<MultipartFile> files = new ArrayList<>();
            if (filesParam != null) files.addAll(filesParam);
            if (singleFile != null) files.add(singleFile);

            if (files.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No files provided"));
            }

            List<Map<String, Object>> results = new ArrayList<>();

            for (MultipartFile file : files) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("filename", file.getOriginalFilename());

                if (file.isEmpty()) {
                    result.put("success", false);
                    result.put("error", "File is empty");
                    results.add(result);
                    continue;
                }

                String contentType = file.getContentType();
                if (contentType == null || !contentType.equals("application/pdf")) {
                    result.put("success", false);
                    result.put("error", "File must be a PDF");
                    results.add(result);
                    continue;
                }

                byte[] fileContent = file.getBytes();
                String filename = workspaceService.savePdfFile(file.getOriginalFilename(), fileContent);

                Document document = new Document();
                document.setId(UUID.randomUUID().toString());
                document.setFilename(filename);
                document.setOriginalFilename(file.getOriginalFilename());
                document.setFilesize(file.getSize());
                document.setCreatedAt(LocalDateTime.now());
                document.setUpdatedAt(LocalDateTime.now());

                Document savedDoc = workspaceService.saveDocument(document);

                jobExecutorService.queueGenerateDocumentData(savedDoc.getId());
                jobExecutorService.queueGenerateThumbnails(savedDoc.getId());
                jobExecutorService.queueDetectTables(savedDoc.getId());

                log.info("Document uploaded: {} ({})", file.getOriginalFilename(), savedDoc.getId());

                result.put("success", true);
                result.put("file_id", savedDoc.getId());
                results.add(result);
            }

            return ResponseEntity.status(HttpStatus.CREATED).body(results);
        } catch (IOException e) {
            log.error("Failed to upload document(s)", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to upload file: " + e.getMessage()));
        }
    }

    /**
     * POST /api/documents/{id}/extract - Extract tables from document.
     * Accepts form-encoded body: coords (JSON string), format, new_filename.
     * Returns [{spec_index, data: [[{text}]], extraction_method}] for AJAX preview,
     * or a CSV/TSV download when format is specified via query param.
     */
    @PostMapping("/{id}/extract")
    public ResponseEntity<?> extractTables(@PathVariable String id,
                                           @RequestParam("coords") String coordsJson,
                                           @RequestParam(value = "format", defaultValue = "json") String format,
                                           @RequestParam(value = "new_filename", required = false) String newFilename) {
        long startedAt = System.currentTimeMillis();
        try {
            log.info("extractTables request: documentId={}, format={}, coordsLength={}, newFilename={}",
                id, format, coordsJson == null ? 0 : coordsJson.length(), newFilename);

            Optional<Document> docOpt = workspaceService.getDocument(id);
            if (docOpt.isEmpty()) {
                log.warn("extractTables failed: document not found, documentId={}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Document not found"));
            }

            Document document = docOpt.get();
            Path pdfPath = workspaceService.getPdfPath(document.getId());

            List<Map<String, Object>> coords = gson.fromJson(coordsJson,
                new com.google.gson.reflect.TypeToken<List<Map<String, Object>>>(){}.getType());

            if (coords == null || coords.isEmpty()) {
                log.warn("extractTables failed: empty/invalid coords, documentId={}", id);
                return ResponseEntity.badRequest().body(Map.of("error", "No coordinates provided"));
            }

            // Group coords by page
            Map<Integer, List<Integer>> pageToIndices = new LinkedHashMap<>();
            for (int i = 0; i < coords.size(); i++) {
                int page = ((Number) coords.get(i).getOrDefault("page", 1)).intValue();
                pageToIndices.computeIfAbsent(page, k -> new ArrayList<>()).add(i);
            }

            log.info("extractTables parsed coords: documentId={}, coordCount={}, pageGroups={}",
                id, coords.size(), pageToIndices.keySet());

            List<Map<String, Object>> results = new ArrayList<>();

            for (Map.Entry<Integer, List<Integer>> entry : pageToIndices.entrySet()) {
                int pageNumber = entry.getKey();
                List<Integer> indices = entry.getValue();
                List<Map<String, Object>> pageSelections = indices.stream()
                    .map(coords::get).collect(Collectors.toList());

                String extractionMethod = (String) pageSelections.get(0).getOrDefault("extraction_method", "guess");
                log.info("extractTables page start: documentId={}, page={}, selectionCount={}, extractionMethod={}",
                    id, pageNumber, pageSelections.size(), extractionMethod);

                List<String> csvResults = tabulaWrapper.extractTablesFromPage(
                    pdfPath, pageNumber, pageSelections, "csv", extractionMethod);

                log.info("extractTables page result: documentId={}, page={}, csvTablesReturned={}",
                    id, pageNumber, csvResults == null ? 0 : csvResults.size());

                for (int j = 0; j < indices.size(); j++) {
                    String csv = j < csvResults.size() ? csvResults.get(j) : "";
                    List<List<Map<String, Object>>> tableData = parseCsvToTableData(csv);

                    if (csv == null || csv.trim().isEmpty()) {
                        log.warn("extractTables empty CSV payload: documentId={}, page={}, specIndex={}, selection={}",
                            id, pageNumber, indices.get(j), pageSelections.get(j));
                    } else {
                        int csvLineCount = csv.split("\\n", -1).length;
                        log.info("extractTables CSV payload summary: documentId={}, page={}, specIndex={}, csvChars={}, csvLines={}",
                            id, pageNumber, indices.get(j), csv.length(), csvLineCount);
                    }

                    if (tableData.isEmpty()) {
                        log.warn("extractTables parsed empty tableData: documentId={}, page={}, specIndex={}, csvSnippet={}",
                            id, pageNumber, indices.get(j), csv == null ? null : csv.substring(0, Math.min(120, csv.length())));
                    }

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("spec_index", indices.get(j));
                    result.put("data", tableData);
                    result.put("extraction_method", extractionMethod.equals("guess") ? "original" : extractionMethod);
                    results.add(result);

                    log.info("extractTables selection result: documentId={}, specIndex={}, rowCount={}",
                        id, indices.get(j), tableData.size());
                }
            }

            // Sort results by spec_index
            results.sort(Comparator.comparingInt(r -> (Integer) r.get("spec_index")));

            // If a non-JSON download format is requested, convert and return as file
            if (!format.equals("json")) {
                String filename = (newFilename != null && !newFilename.isEmpty())
                    ? newFilename.replaceAll("\\.[^.]+$", "") : document.getOriginalFilename().replaceAll("\\.[^.]+$", "");
                String content = convertResultsToFormat(results, format);
                MediaType mediaType = format.equals("tsv") ? MediaType.TEXT_PLAIN : MediaType.parseMediaType("text/csv");
                log.info("extractTables completed (download): documentId={}, format={}, selectionResults={}, elapsedMs={}",
                    id, format, results.size(), System.currentTimeMillis() - startedAt);
                return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "." + format + "\"")
                    .contentType(mediaType)
                    .body(content);
            }

            log.info("extractTables completed: documentId={}, format=json, selectionResults={}, elapsedMs={}",
                id, results.size(), System.currentTimeMillis() - startedAt);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Failed to extract tables: documentId={}, format={}, coordsLength={}",
                id, format, coordsJson == null ? 0 : coordsJson.length(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to extract tables: " + e.getMessage()));
        }
    }

    private List<List<Map<String, Object>>> parseCsvToTableData(String csv) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) return rows;
        for (String line : csv.split("\n")) {
            if (line.trim().isEmpty()) continue;
            List<Map<String, Object>> row = new ArrayList<>();
            for (String cell : line.split(",", -1)) {
                Map<String, Object> cellData = new LinkedHashMap<>();
                cellData.put("text", cell.trim().replaceAll("^\"|\"$", ""));
                cellData.put("width", 0);
                cellData.put("height", 0);
                row.add(cellData);
            }
            rows.add(row);
        }
        return rows;
    }

    private String convertResultsToFormat(List<Map<String, Object>> results, String format) {
        String delimiter = format.equals("tsv") ? "\t" : ",";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> result : results) {
            List<List<Map<String, Object>>> data = (List<List<Map<String, Object>>>) result.get("data");
            if (data == null) continue;
            for (List<Map<String, Object>> row : data) {
                sb.append(row.stream()
                    .map(cell -> {
                        String text = String.valueOf(cell.getOrDefault("text", ""));
                        if (text.contains(delimiter) || text.contains("\"") || text.contains("\n")) {
                            text = "\"" + text.replace("\"", "\"\"") + "\"";
                        }
                        return text;
                    })
                    .collect(Collectors.joining(delimiter)));
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * GET /api/documents/{id}/detect-tables - Get auto-detected table regions for all pages.
     * Returns {status, document_id, tables} where tables is a map of pageNum -> [{x1,y1,x2,y2,...}].
     */
    @GetMapping("/{id}/detect-tables")
    public ResponseEntity<?> getDetectedTables(@PathVariable String id) {
        Optional<Document> docOpt = workspaceService.getDocument(id);
        if (docOpt.isEmpty()) {
            log.warn("getDetectedTables failed: document not found, documentId={}", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Document not found"));
        }

        Document document = docOpt.get();

        List<Job> jobs = jobExecutorService.getJobsByDocumentId(id);
        Optional<Job> detectTablesJob = jobs.stream()
            .filter(j -> j.getType() == Job.JobType.DETECT_TABLES)
            .findFirst();

        if (detectTablesJob.isEmpty()) {
            Job queued = jobExecutorService.queueDetectTables(id);
            log.info("getDetectedTables pending: documentId={}, jobStatus=MISSING, requeuedJobId={}", id, queued.getId());
            return ResponseEntity.ok(Map.of("status", "pending", "tables", Collections.emptyList()));
        }

        Job job = detectTablesJob.get();
        if (job.getStatus() == Job.JobStatus.FAILED) {
            Job queued = jobExecutorService.queueDetectTables(id);
            log.warn("getDetectedTables pending: documentId={}, jobStatus=FAILED, requeuedJobId={}, error={}",
                id, queued.getId(), job.getErrorMessage());
            return ResponseEntity.ok(Map.of("status", "pending", "tables", Collections.emptyList()));
        }

        if (job.getStatus() != Job.JobStatus.COMPLETED) {
            log.info("getDetectedTables pending: documentId={}, jobStatus={}", id, job.getStatus());
            return ResponseEntity.ok(Map.of("status", "pending", "tables", Collections.emptyList()));
        }

        // Parse stored result: {"1": [{top, left, width, height, ...}], ...}
        // Convert to old-style array-of-arrays for the frontend: [[x,y,w,h], ...]  per page index
        String resultJson = detectTablesJob.get().getResult();
        Map<String, List<Map<String, Object>>> perPage = gson.fromJson(resultJson,
            new com.google.gson.reflect.TypeToken<Map<String, List<Map<String, Object>>>>(){}.getType());

        int pageCount = Math.max(document.getPageCount(), 0);
        int maxPageFromResults = perPage.keySet().stream().mapToInt(Integer::parseInt).max().orElse(0);
        int maxPage = Math.max(pageCount, maxPageFromResults);
        List<List<List<Double>>> pagesArray = new ArrayList<>();
        for (int p = 1; p <= maxPage; p++) {
            List<List<Double>> pageTables = new ArrayList<>();
            List<Map<String, Object>> tables = perPage.get(String.valueOf(p));
            if (tables != null) {
                for (Map<String, Object> t : tables) {
                    double left = ((Number) t.getOrDefault("left", 0)).doubleValue();
                    double top = ((Number) t.getOrDefault("top", 0)).doubleValue();
                    double width = ((Number) t.getOrDefault("width", 0)).doubleValue();
                    double height = ((Number) t.getOrDefault("height", 0)).doubleValue();
                    pageTables.add(Arrays.asList(left, top, width, height));
                }
            }
            pagesArray.add(pageTables);
        }

        int totalTables = pagesArray.stream().mapToInt(List::size).sum();
        log.info("getDetectedTables completed: documentId={}, pages={}, totalTables={}",
            id, pagesArray.size(), totalTables);

        return ResponseEntity.ok(pagesArray);
    }

    /**
     * GET /api/documents/{id}/pages - Return page dimensions for all pages.
     * Returns [{number, width, height, rotation}].
     */
    @GetMapping("/{id}/pages")
    public ResponseEntity<?> getPages(@PathVariable String id) {
        Optional<Document> docOpt = workspaceService.getDocument(id);
        if (docOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Document not found"));
        }
        try {
            Path pdfPath = workspaceService.getPdfPath(id);
            List<Map<String, Object>> pages = tabulaWrapper.getPageDimensions(pdfPath);
            return ResponseEntity.ok(pages);
        } catch (Exception e) {
            log.error("Failed to get page dimensions for document {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to get pages: " + e.getMessage()));
        }
    }

    /**
     * GET /api/documents/{id}/thumbnail/{pageNumber} - Return a PNG thumbnail for a page.
     */
    @GetMapping(value = "/{id}/thumbnail/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getThumbnail(@PathVariable String id,
                                               @PathVariable int pageNumber) {
        Optional<Document> docOpt = workspaceService.getDocument(id);
        if (docOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            Path pdfPath = workspaceService.getPdfPath(id);
            Path thumbDir = config.getWorkspaceDirectory().resolve("thumbnails");
            byte[] imageBytes = tabulaWrapper.generateThumbnailBytes(pdfPath, id, pageNumber, thumbDir);
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(imageBytes);
        } catch (Exception e) {
            log.error("Failed to generate thumbnail for document {} page {}", id, pageNumber, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * DELETE /api/documents/{id} - Delete a document.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable String id) {
        try {
            Optional<Document> docOpt = workspaceService.getDocument(id);
            if (docOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Document not found"));
            }
            
            workspaceService.deleteDocument(id);
            
            log.info("Document deleted: {}", id);
            
            return ResponseEntity.ok(Map.of("message", "Document deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete document", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to delete document: " + e.getMessage()));
        }
    }

}
