package com.tabula.model;

import com.google.gson.annotations.SerializedName;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a saved extraction template.
 */
public class Template {

    @SerializedName("id")
    private String id;

    @SerializedName("name")
    private String name;

    @SerializedName("document_id")
    private String documentId;

    @SerializedName("created_at")
    private LocalDateTime createdAt;

    @SerializedName("updated_at")
    private LocalDateTime updatedAt;

    @SerializedName("extraction_method")
    private String extractionMethod;

    @SerializedName("selections")
    private List<PageSelection> selections;

    // Constructors
    public Template() {}

    public Template(String id, String name, String documentId, LocalDateTime createdAt,
                   LocalDateTime updatedAt, String extractionMethod, List<PageSelection> selections) {
        this.id = id;
        this.name = name;
        this.documentId = documentId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.extractionMethod = extractionMethod;
        this.selections = selections;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getExtractionMethod() { return extractionMethod; }
    public void setExtractionMethod(String extractionMethod) { this.extractionMethod = extractionMethod; }

    public List<PageSelection> getSelections() { return selections; }
    public void setSelections(List<PageSelection> selections) { this.selections = selections; }

    /**
     * Represents a user's rectangular selection on a PDF page.
     */
    public static class PageSelection {
        @SerializedName("page")
        private int page;

        @SerializedName("top")
        private double top;

        @SerializedName("left")
        private double left;

        @SerializedName("bottom")
        private double bottom;

        @SerializedName("right")
        private double right;

        @SerializedName("width")
        private double width;

        @SerializedName("height")
        private double height;

        // Constructors
        public PageSelection() {}

        public PageSelection(int page, double top, double left, double bottom, double right, double width, double height) {
            this.page = page;
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
            this.width = width;
            this.height = height;
        }

        // Getters and Setters
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }

        public double getTop() { return top; }
        public void setTop(double top) { this.top = top; }

        public double getLeft() { return left; }
        public void setLeft(double left) { this.left = left; }

        public double getBottom() { return bottom; }
        public void setBottom(double bottom) { this.bottom = bottom; }

        public double getRight() { return right; }
        public void setRight(double right) { this.right = right; }

        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }

        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }
    }
}
