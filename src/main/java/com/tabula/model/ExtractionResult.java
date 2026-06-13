package com.tabula.model;

/**
 * Represents an extracted table result.
 */
public class ExtractionResult {

    private String documentId;
    private int pageNumber;
    private String format; // csv, json, tsv
    private String data; // The actual extracted data as string
    private long processingTimeMs;
    private boolean success;
    private String errorMessage;

    // Constructors
    public ExtractionResult() {}

    public ExtractionResult(String documentId, int pageNumber, String format, String data,
                           long processingTimeMs, boolean success, String errorMessage) {
        this.documentId = documentId;
        this.pageNumber = pageNumber;
        this.format = format;
        this.data = data;
        this.processingTimeMs = processingTimeMs;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    // Getters and Setters
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public int getPageNumber() { return pageNumber; }
    public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
