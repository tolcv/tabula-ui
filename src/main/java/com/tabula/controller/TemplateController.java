package com.tabula.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tabula.model.Template;
import com.tabula.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * REST API endpoints for extraction template management.
 */
@RestController
@RequestMapping("/api/templates")
@CrossOrigin(origins = "*")
public class TemplateController {

    private static final Logger log = LoggerFactory.getLogger(TemplateController.class);

    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public TemplateController(WorkspaceService workspaceService, ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/templates - Get all templates.
     */
    @GetMapping
    public ResponseEntity<List<Template>> getAllTemplates() {
        List<Template> templates = workspaceService.getAllTemplates();
        return ResponseEntity.ok(templates);
    }

    /**
     * GET /api/templates/document/{documentId} - Get templates for a document.
     */
    @GetMapping("/document/{documentId}")
    public ResponseEntity<List<Template>> getTemplatesByDocument(@PathVariable String documentId) {
        List<Template> templates = workspaceService.getTemplatesByDocumentId(documentId);
        return ResponseEntity.ok(templates);
    }

    /**
     * GET /api/templates/{id} - Get a specific template.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getTemplate(@PathVariable String id) {
        Optional<Template> template = workspaceService.getTemplate(id);
        
        if (template.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Template not found"));
        }
        
        return ResponseEntity.ok(template.get());
    }

    /**
     * POST /api/templates - Create a new template.
     */
    @PostMapping
    public ResponseEntity<?> createTemplate(@RequestBody Template template) {
        try {
            if (template.getDocumentId() == null || template.getDocumentId().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Document ID is required"));
            }
            
            if (template.getName() == null || template.getName().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Template name is required"));
            }
            
            Template savedTemplate = workspaceService.saveTemplate(template);
            
            log.info("Template created: {} ({})", template.getName(), savedTemplate.getId());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(savedTemplate);
        } catch (Exception e) {
            log.error("Failed to create template", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to create template: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/templates/{id} - Update an existing template.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateTemplate(@PathVariable String id,
                                           @RequestBody Template template) {
        try {
            Optional<Template> existing = workspaceService.getTemplate(id);
            if (existing.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Template not found"));
            }
            
            template.setId(id);
            template.setCreatedAt(existing.get().getCreatedAt());
            
            Template updatedTemplate = workspaceService.saveTemplate(template);
            
            log.info("Template updated: {}", id);
            
            return ResponseEntity.ok(updatedTemplate);
        } catch (Exception e) {
            log.error("Failed to update template", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to update template: " + e.getMessage()));
        }
    }

    /**
     * POST /api/templates/upload - Upload a template JSON file.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadTemplate(@RequestParam("file") MultipartFile file) {
        try {
            Template template = objectMapper.readValue(file.getInputStream(), Template.class);
            if (template.getName() == null || template.getName().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Template name is required"));
            }
            Template saved = workspaceService.saveTemplate(template);
            log.info("Template uploaded: {} ({})", saved.getName(), saved.getId());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("template_id", saved.getId()));
        } catch (IOException e) {
            log.error("Failed to parse uploaded template", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Invalid template JSON: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to save uploaded template", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to save template: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/templates/{id} - Delete a template.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTemplate(@PathVariable String id) {
        try {
            Optional<Template> template = workspaceService.getTemplate(id);
            if (template.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Template not found"));
            }
            
            workspaceService.deleteTemplate(id);
            
            log.info("Template deleted: {}", id);
            
            return ResponseEntity.ok(Map.of("message", "Template deleted successfully"));
        } catch (Exception e) {
            log.error("Failed to delete template", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to delete template: " + e.getMessage()));
        }
    }

}
