package com.tabula.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application configuration for Tabula.
 * Handles workspace directory management, feature flags, and thread pool configuration.
 */
@Configuration
@ConfigurationProperties(prefix = "tabula")
public class ApplicationConfiguration {

    @Value("${tabula.disable.version-check:false}")
    private boolean disableVersionCheck;

    @Value("${tabula.disable.notifications:false}")
    private boolean disableNotifications;

    @Value("${tabula.thumbnail.generation.enabled:true}")
    private boolean thumbnailGenerationEnabled;

    @Value("${tabula.table-detection.enabled:true}")
    private boolean tableDetectionEnabled;

    @Value("${tabula.thread-pool.core-size:3}")
    private int threadPoolCoreSize;

    @Value("${tabula.thread-pool.max-size:5}")
    private int threadPoolMaxSize;

    @Value("${tabula.thread-pool.queue-capacity:100}")
    private int threadPoolQueueCapacity;

    @Value("${tabula.pdf.max-pages:1000}")
    private int pdfMaxPages;

    private static final String WORKSPACE_DIR_NAME = "tabula";
    private static final String PDFS_SUBDIR = "pdfs";
    private static final String TEMPLATES_SUBDIR = "templates";

    /**
     * Get the workspace directory path (OS-specific).
     * - macOS: ~/Library/Application Support/Tabula
     * - Windows: %APPDATA%/Tabula
     * - Linux: ~/.tabula or $XDG_DATA_HOME/tabula
     */
    public Path getWorkspaceDirectory() {
        String customPath = System.getProperty("tabula.workspace.directory");
        if (customPath != null && !customPath.isEmpty()) {
            return Paths.get(customPath);
        }

        String osName = System.getProperty("os.name").toLowerCase();
        Path workspacePath;

        if (osName.contains("mac")) {
            // macOS: ~/Library/Application Support/Tabula
            String home = System.getProperty("user.home");
            workspacePath = Paths.get(home, "Library", "Application Support", WORKSPACE_DIR_NAME);
        } else if (osName.contains("win")) {
            // Windows: %APPDATA%/Tabula
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                workspacePath = Paths.get(appData, WORKSPACE_DIR_NAME);
            } else {
                String home = System.getProperty("user.home");
                workspacePath = Paths.get(home, "AppData", "Roaming", WORKSPACE_DIR_NAME);
            }
        } else {
            // Linux and others: ~/.tabula or $XDG_DATA_HOME/tabula
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            if (xdgDataHome != null && !xdgDataHome.isEmpty()) {
                workspacePath = Paths.get(xdgDataHome, WORKSPACE_DIR_NAME);
            } else {
                String home = System.getProperty("user.home");
                workspacePath = Paths.get(home, "." + WORKSPACE_DIR_NAME);
            }
        }

        // Create directory if it doesn't exist
        try {
            Files.createDirectories(workspacePath);
        } catch (Exception e) {
            // If creation fails, fallback to temp directory
            workspacePath = Paths.get(System.getProperty("java.io.tmpdir"), WORKSPACE_DIR_NAME);
            try {
                Files.createDirectories(workspacePath);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to create workspace directory: " + workspacePath, ex);
            }
        }

        return workspacePath;
    }

    /**
     * Get the PDFs subdirectory path.
     */
    public Path getPdfsDirectory() {
        Path pdfsDir = getWorkspaceDirectory().resolve(PDFS_SUBDIR);
        try {
            Files.createDirectories(pdfsDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create PDFs directory: " + pdfsDir, e);
        }
        return pdfsDir;
    }

    /**
     * Get the templates subdirectory path.
     */
    public Path getTemplatesDirectory() {
        Path templatesDir = getWorkspaceDirectory().resolve(TEMPLATES_SUBDIR);
        try {
            Files.createDirectories(templatesDir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create templates directory: " + templatesDir, e);
        }
        return templatesDir;
    }

    /**
     * Get the workspace.json file path.
     */
    public Path getWorkspaceJsonFile() {
        return getWorkspaceDirectory().resolve("workspace.json");
    }

    /**
     * Create and configure the thread pool executor for background jobs.
     */
    public ThreadPoolTaskExecutor createJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threadPoolCoreSize);
        executor.setMaxPoolSize(threadPoolMaxSize);
        executor.setQueueCapacity(threadPoolQueueCapacity);
        executor.setThreadNamePrefix("tabula-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Print debug information about workspace configuration.
     */
    public void logConfiguration() {
        System.out.println("=== Tabula Configuration ===");
        System.out.println("Workspace Directory: " + getWorkspaceDirectory());
        System.out.println("PDFs Directory: " + getPdfsDirectory());
        System.out.println("Templates Directory: " + getTemplatesDirectory());
        System.out.println("Version Check Disabled: " + disableVersionCheck);
        System.out.println("Notifications Disabled: " + disableNotifications);
        System.out.println("Thumbnail Generation: " + thumbnailGenerationEnabled);
        System.out.println("Table Detection: " + tableDetectionEnabled);
        System.out.println("Thread Pool Size: " + threadPoolCoreSize + "-" + threadPoolMaxSize);
        System.out.println("============================");
    }

    // Getters
    public boolean isDisableVersionCheck() { return disableVersionCheck; }
    public void setDisableVersionCheck(boolean disableVersionCheck) { this.disableVersionCheck = disableVersionCheck; }

    public boolean isDisableNotifications() { return disableNotifications; }
    public void setDisableNotifications(boolean disableNotifications) { this.disableNotifications = disableNotifications; }

    public boolean isThumbnailGenerationEnabled() { return thumbnailGenerationEnabled; }
    public void setThumbnailGenerationEnabled(boolean thumbnailGenerationEnabled) { this.thumbnailGenerationEnabled = thumbnailGenerationEnabled; }

    public boolean isTableDetectionEnabled() { return tableDetectionEnabled; }
    public void setTableDetectionEnabled(boolean tableDetectionEnabled) { this.tableDetectionEnabled = tableDetectionEnabled; }

    public int getThreadPoolCoreSize() { return threadPoolCoreSize; }
    public void setThreadPoolCoreSize(int threadPoolCoreSize) { this.threadPoolCoreSize = threadPoolCoreSize; }

    public int getThreadPoolMaxSize() { return threadPoolMaxSize; }
    public void setThreadPoolMaxSize(int threadPoolMaxSize) { this.threadPoolMaxSize = threadPoolMaxSize; }

    public int getThreadPoolQueueCapacity() { return threadPoolQueueCapacity; }
    public void setThreadPoolQueueCapacity(int threadPoolQueueCapacity) { this.threadPoolQueueCapacity = threadPoolQueueCapacity; }

    public int getPdfMaxPages() { return pdfMaxPages; }
    public void setPdfMaxPages(int pdfMaxPages) { this.pdfMaxPages = pdfMaxPages; }
}
