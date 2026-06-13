package com.tabula.model;

import java.time.LocalDateTime;

/**
 * Represents a background job in the queue.
 */
public class Job {

    public enum JobType {
        GENERATE_DOCUMENT_DATA,
        GENERATE_THUMBNAILS,
        DETECT_TABLES,
        EXTRACT_TABLES
    }

    public enum JobStatus {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private String id;
    private String documentId;
    private JobType type;
    private JobStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    private int progress; // 0-100
    private String result; // JSON string with job-specific result data

    // Constructors
    public Job() {}

    public Job(String id, String documentId, JobType type, JobStatus status,
              LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime completedAt,
              String errorMessage, int progress, String result) {
        this.id = id;
        this.documentId = documentId;
        this.type = type;
        this.status = status;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.errorMessage = errorMessage;
        this.progress = progress;
        this.result = result;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public JobType getType() { return type; }
    public void setType(JobType type) { this.type = type; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
}
