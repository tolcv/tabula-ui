package com.tabula.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.annotations.SerializedName;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Represents a PDF document in the Tabula workspace.
 */
public class Document {

    @SerializedName("id")
    private String id;

    @SerializedName("filename")
    private String filename;

    @SerializedName("original_filename")
    private String originalFilename;

    @SerializedName("filesize")
    private long filesize;

    @SerializedName("page_count")
    private int pageCount;

    @SerializedName("created_at")
    private LocalDateTime createdAt;

    @SerializedName("updated_at")
    private LocalDateTime updatedAt;

    @SerializedName("thumbnails_generated")
    private boolean thumbnailsGenerated;

    @SerializedName("tables_detected")
    private boolean tablesDetected;

    @SerializedName("extraction_templates")
    private List<String> extractionTemplates;

    @SerializedName("metadata")
    private DocumentMetadata metadata;

    // Constructors
    public Document() {}

    public Document(String id, String filename, String originalFilename, long filesize,
                   int pageCount, LocalDateTime createdAt, LocalDateTime updatedAt,
                   boolean thumbnailsGenerated, boolean tablesDetected,
                   List<String> extractionTemplates, DocumentMetadata metadata) {
        this.id = id;
        this.filename = filename;
        this.originalFilename = originalFilename;
        this.filesize = filesize;
        this.pageCount = pageCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.thumbnailsGenerated = thumbnailsGenerated;
        this.tablesDetected = tablesDetected;
        this.extractionTemplates = extractionTemplates;
        this.metadata = metadata;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public long getFilesize() { return filesize; }
    public void setFilesize(long filesize) { this.filesize = filesize; }

    /** Alias for filesize, used by the frontend. */
    @JsonProperty("size")
    public long getSize() { return filesize; }

    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /** Unix epoch seconds, used by the frontend for sorting and display. */
    @JsonProperty("time")
    public long getTime() {
        if (createdAt == null) return 0;
        return createdAt.toEpochSecond(ZoneOffset.UTC);
    }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean isThumbnailsGenerated() { return thumbnailsGenerated; }
    public void setThumbnailsGenerated(boolean thumbnailsGenerated) { this.thumbnailsGenerated = thumbnailsGenerated; }

    public boolean isTablesDetected() { return tablesDetected; }
    public void setTablesDetected(boolean tablesDetected) { this.tablesDetected = tablesDetected; }

    public List<String> getExtractionTemplates() { return extractionTemplates; }
    public void setExtractionTemplates(List<String> extractionTemplates) { this.extractionTemplates = extractionTemplates; }

    public DocumentMetadata getMetadata() { return metadata; }
    public void setMetadata(DocumentMetadata metadata) { this.metadata = metadata; }

    /**
     * Metadata extracted from PDF during processing.
     */
    public static class DocumentMetadata {
        @SerializedName("title")
        private String title;

        @SerializedName("author")
        private String author;

        @SerializedName("subject")
        private String subject;

        @SerializedName("creator")
        private String creator;

        @SerializedName("producer")
        private String producer;

        @SerializedName("creation_date")
        private String creationDate;

        @SerializedName("modification_date")
        private String modificationDate;

        // Constructors
        public DocumentMetadata() {}

        public DocumentMetadata(String title, String author, String subject, String creator,
                               String producer, String creationDate, String modificationDate) {
            this.title = title;
            this.author = author;
            this.subject = subject;
            this.creator = creator;
            this.producer = producer;
            this.creationDate = creationDate;
            this.modificationDate = modificationDate;
        }

        // Getters and Setters
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }

        public String getCreator() { return creator; }
        public void setCreator(String creator) { this.creator = creator; }

        public String getProducer() { return producer; }
        public void setProducer(String producer) { this.producer = producer; }

        public String getCreationDate() { return creationDate; }
        public void setCreationDate(String creationDate) { this.creationDate = creationDate; }

        public String getModificationDate() { return modificationDate; }
        public void setModificationDate(String modificationDate) { this.modificationDate = modificationDate; }
    }
}
