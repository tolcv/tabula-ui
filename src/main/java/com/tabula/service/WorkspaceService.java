package com.tabula.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tabula.config.ApplicationConfiguration;
import com.tabula.model.Document;
import com.tabula.model.Template;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages persistent storage of PDFs, templates, and workspace metadata.
 * Replaces Ruby's lib/tabula_workspace.rb
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final ApplicationConfiguration config;
    private final Gson gson;
    private final Map<String, Document> documentsCache; // In-memory cache of documents
    private final Map<String, Template> templatesCache; // In-memory cache of templates

    public WorkspaceService(ApplicationConfiguration config, Gson gson) {
        this.config = config;
        this.gson = gson;
        this.documentsCache = Collections.synchronizedMap(new LinkedHashMap<>());
        this.templatesCache = Collections.synchronizedMap(new LinkedHashMap<>());
        
        // Initialize workspace directories and load existing data
        initializeWorkspace();
    }

    /**
     * Initialize workspace directories and load existing documents/templates from disk.
     */
    private void initializeWorkspace() {
        try {
            config.logConfiguration();
            
            // Create directories
            Files.createDirectories(config.getPdfsDirectory());
            Files.createDirectories(config.getTemplatesDirectory());
            
            log.info("Workspace initialized at: {}", config.getWorkspaceDirectory());
            
            // Load existing documents and templates from workspace.json
            loadWorkspaceMetadata();
        } catch (IOException e) {
            log.error("Failed to initialize workspace", e);
            throw new RuntimeException("Failed to initialize workspace", e);
        }
    }

    /**
     * Load workspace metadata from workspace.json file.
     */
    private synchronized void loadWorkspaceMetadata() {
        Path workspaceJsonPath = config.getWorkspaceJsonFile();
        
        if (!Files.exists(workspaceJsonPath)) {
            log.info("No existing workspace.json found, creating new one");
            saveWorkspaceMetadata();
            return;
        }

        try {
            String content = new String(Files.readAllBytes(workspaceJsonPath), StandardCharsets.UTF_8);
            Map<String, Object> metadata = gson.fromJson(content, 
                new TypeToken<Map<String, Object>>(){}.getType());

            if (metadata != null && metadata.containsKey("documents")) {
                List<Map<String, Object>> docsJson = (List<Map<String, Object>>) metadata.get("documents");
                for (Map<String, Object> docJson : docsJson) {
                    Document doc = gson.fromJson(gson.toJson(docJson), Document.class);
                    documentsCache.put(doc.getId(), doc);
                }
                log.info("Loaded {} documents from workspace.json", documentsCache.size());
            }

            if (metadata != null && metadata.containsKey("templates")) {
                List<Map<String, Object>> templatesJson = (List<Map<String, Object>>) metadata.get("templates");
                for (Map<String, Object> templateJson : templatesJson) {
                    Template template = gson.fromJson(gson.toJson(templateJson), Template.class);
                    templatesCache.put(template.getId(), template);
                }
                log.info("Loaded {} templates from workspace.json", templatesCache.size());
            }
        } catch (Exception e) {
            log.error("Failed to load workspace metadata", e);
            // If loading fails, start fresh
            documentsCache.clear();
            templatesCache.clear();
        }
    }

    /**
     * Save workspace metadata to workspace.json file.
     */
    private synchronized void saveWorkspaceMetadata() {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("documents", new ArrayList<>(documentsCache.values()));
            metadata.put("templates", new ArrayList<>(templatesCache.values()));
            metadata.put("last_updated", LocalDateTime.now());

            String json = gson.toJson(metadata);
            Files.write(config.getWorkspaceJsonFile(), json.getBytes(StandardCharsets.UTF_8));
            log.debug("Saved workspace metadata to workspace.json");
        } catch (IOException e) {
            log.error("Failed to save workspace metadata", e);
            throw new RuntimeException("Failed to save workspace metadata", e);
        }
    }

    // ===== Document Operations =====

    /**
     * Get all documents in the workspace.
     */
    public List<Document> getAllDocuments() {
        return new ArrayList<>(documentsCache.values());
    }

    /**
     * Get a specific document by ID.
     */
    public Optional<Document> getDocument(String documentId) {
        return Optional.ofNullable(documentsCache.get(documentId));
    }

    /**
     * Save or update a document.
     */
    public synchronized Document saveDocument(Document document) {
        if (document.getId() == null) {
            document.setId(UUID.randomUUID().toString());
        }
        
        if (document.getCreatedAt() == null) {
            document.setCreatedAt(LocalDateTime.now());
        }
        
        document.setUpdatedAt(LocalDateTime.now());
        
        documentsCache.put(document.getId(), document);
        saveWorkspaceMetadata();
        
        log.info("Saved document: {} ({})", document.getOriginalFilename(), document.getId());
        return document;
    }

    /**
     * Delete a document and its associated files.
     */
    public synchronized void deleteDocument(String documentId) {
        Document document = documentsCache.get(documentId);
        
        if (document == null) {
            log.warn("Document not found: {}", documentId);
            return;
        }

        try {
            // Delete PDF file
            Path pdfPath = config.getPdfsDirectory().resolve(document.getFilename());
            if (Files.exists(pdfPath)) {
                Files.delete(pdfPath);
                log.debug("Deleted PDF file: {}", pdfPath);
            }

            // Delete associated templates
            List<Template> associatedTemplates = getTemplatesByDocumentId(documentId);
            for (Template template : associatedTemplates) {
                deleteTemplate(template.getId());
            }

            // Remove from cache
            documentsCache.remove(documentId);
            saveWorkspaceMetadata();
            
            log.info("Deleted document: {}", documentId);
        } catch (IOException e) {
            log.error("Failed to delete document", e);
            throw new RuntimeException("Failed to delete document", e);
        }
    }

    // ===== Template Operations =====

    /**
     * Get all templates.
     */
    public List<Template> getAllTemplates() {
        return new ArrayList<>(templatesCache.values());
    }

    /**
     * Get templates for a specific document.
     */
    public List<Template> getTemplatesByDocumentId(String documentId) {
        return templatesCache.values().stream()
            .filter(t -> t.getDocumentId().equals(documentId))
            .collect(Collectors.toList());
    }

    /**
     * Get a specific template by ID.
     */
    public Optional<Template> getTemplate(String templateId) {
        return Optional.ofNullable(templatesCache.get(templateId));
    }

    /**
     * Save or update a template.
     */
    public synchronized Template saveTemplate(Template template) {
        if (template.getId() == null) {
            template.setId(UUID.randomUUID().toString());
        }
        
        if (template.getCreatedAt() == null) {
            template.setCreatedAt(LocalDateTime.now());
        }
        
        template.setUpdatedAt(LocalDateTime.now());
        
        templatesCache.put(template.getId(), template);
        saveWorkspaceMetadata();
        
        log.info("Saved template: {} ({})", template.getName(), template.getId());
        return template;
    }

    /**
     * Delete a template.
     */
    public synchronized void deleteTemplate(String templateId) {
        templatesCache.remove(templateId);
        saveWorkspaceMetadata();
        
        log.info("Deleted template: {}", templateId);
    }

    // ===== File Operations =====

    /**
     * Get path to a PDF file.
     */
    public Path getPdfPath(String documentId) {
        Optional<Document> doc = getDocument(documentId);
        if (doc.isPresent()) {
            return config.getPdfsDirectory().resolve(doc.get().getFilename());
        }
        throw new RuntimeException("Document not found: " + documentId);
    }

    /**
     * Save uploaded PDF file to workspace.
     */
    public synchronized String savePdfFile(String originalFilename, byte[] fileContent) throws IOException {
        String filename = UUID.randomUUID() + ".pdf";
        Path pdfPath = config.getPdfsDirectory().resolve(filename);
        
        Files.write(pdfPath, fileContent);
        log.info("Saved PDF file: {} -> {}", originalFilename, filename);
        
        return filename;
    }

    /**
     * Get the content of a PDF file.
     */
    public byte[] readPdfFile(String documentId) throws IOException {
        Path pdfPath = getPdfPath(documentId);
        if (!Files.exists(pdfPath)) {
            throw new RuntimeException("PDF file not found: " + pdfPath);
        }
        return Files.readAllBytes(pdfPath);
    }

    /**
     * Clear all cached data (for testing purposes).
     */
    public synchronized void clearCache() {
        documentsCache.clear();
        templatesCache.clear();
        log.warn("Cleared workspace cache");
    }

    /**
     * Get workspace statistics.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_documents", documentsCache.size());
        stats.put("total_templates", templatesCache.size());
        stats.put("workspace_directory", config.getWorkspaceDirectory().toString());
        
        long totalSize = 0;
        try {
            totalSize = FileUtils.sizeOfDirectory(config.getWorkspaceDirectory().toFile());
        } catch (Exception e) {
            log.warn("Failed to calculate workspace size", e);
        }
        stats.put("workspace_size_bytes", totalSize);
        
        return stats;
    }

}
