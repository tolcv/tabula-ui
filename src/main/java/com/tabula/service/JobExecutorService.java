package com.tabula.service;

import com.google.gson.Gson;
import com.tabula.config.ApplicationConfiguration;
import com.tabula.model.Document;
import com.tabula.model.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Service for managing background jobs (thumbnail generation, metadata extraction, table detection).
 * Replaces Ruby's lib/tabula_job_executor/executor.rb
 */
@Service
public class JobExecutorService {

    private static final Logger log = LoggerFactory.getLogger(JobExecutorService.class);

    private final WorkspaceService workspaceService;
    private final TabulaJavaWrapper tabulaWrapper;
    private final ApplicationConfiguration config;
    private final Gson gson;
    
    private ThreadPoolTaskExecutor jobExecutor;
    private final Map<String, Job> jobQueue; // In-memory job queue
    private final BlockingQueue<Job> pendingJobs;

    public JobExecutorService(WorkspaceService workspaceService, 
                             TabulaJavaWrapper tabulaWrapper,
                             ApplicationConfiguration config,
                             Gson gson) {
        this.workspaceService = workspaceService;
        this.tabulaWrapper = tabulaWrapper;
        this.config = config;
        this.gson = gson;
        this.jobQueue = Collections.synchronizedMap(new LinkedHashMap<>());
        this.pendingJobs = new LinkedBlockingQueue<>();
    }

    /**
     * Initialize the job executor thread pool after bean construction.
     */
    @PostConstruct
    public void init() {
        this.jobExecutor = config.createJobExecutor();
        log.info("JobExecutorService initialized with thread pool: {}-{}", 
                 config.getThreadPoolCoreSize(), config.getThreadPoolMaxSize());
    }

    /**
     * Submit a job for background execution.
     */
    public Job submitJob(Job job) {
        if (job.getId() == null) {
            job.setId(UUID.randomUUID().toString());
        }
        
        job.setStatus(Job.JobStatus.QUEUED);
        job.setCreatedAt(LocalDateTime.now());
        
        jobQueue.put(job.getId(), job);
        pendingJobs.offer(job);
        
        log.info("Job submitted: {} (type: {})", job.getId(), job.getType());
        return job;
    }

    /**
     * Get a job by ID from the queue.
     */
    public Optional<Job> getJob(String jobId) {
        return Optional.ofNullable(jobQueue.get(jobId));
    }

    /**
     * Get all jobs in the queue.
     */
    public List<Job> getAllJobs() {
        return new ArrayList<>(jobQueue.values());
    }

    /**
     * Get all pending jobs for a document.
     */
    public List<Job> getJobsByDocumentId(String documentId) {
        return jobQueue.values().stream()
            .filter(j -> j.getDocumentId().equals(documentId))
            .toList();
    }

    /**
     * Submit a job to generate document metadata (page count, title, etc.).
     */
    public Job queueGenerateDocumentData(String documentId) {
        Job job = new Job();
        job.setDocumentId(documentId);
        job.setType(Job.JobType.GENERATE_DOCUMENT_DATA);
        
        Job submitted = submitJob(job);
        
        jobExecutor.submit(() -> executeGenerateDocumentData(submitted));
        
        return submitted;
    }

    /**
     * Execute job: Generate document metadata.
     */
    private void executeGenerateDocumentData(Job job) {
        try {
            job.setStatus(Job.JobStatus.RUNNING);
            job.setStartedAt(LocalDateTime.now());
            job.setProgress(10);
            
            Optional<Document> docOpt = workspaceService.getDocument(job.getDocumentId());
            if (docOpt.isEmpty()) {
                throw new RuntimeException("Document not found: " + job.getDocumentId());
            }
            
            Document document = docOpt.get();
            Path pdfPath = workspaceService.getPdfPath(document.getId());
            
            log.info("Generating metadata for document: {}", document.getId());
            
            // Extract metadata
            Map<String, Object> metadata = tabulaWrapper.extractPdfMetadata(pdfPath);
            job.setProgress(50);
            
            // Update document with metadata
            Document.DocumentMetadata docMetadata = new Document.DocumentMetadata();
            docMetadata.setTitle((String) metadata.get("title"));
            docMetadata.setAuthor((String) metadata.get("author"));
            docMetadata.setSubject((String) metadata.get("subject"));
            docMetadata.setCreator((String) metadata.get("creator"));
            docMetadata.setProducer((String) metadata.get("producer"));
            docMetadata.setCreationDate(metadata.get("creation_date") != null ? metadata.get("creation_date").toString() : null);
            docMetadata.setModificationDate(metadata.get("modification_date") != null ? metadata.get("modification_date").toString() : null);
            
            document.setMetadata(docMetadata);
            document.setPageCount((int) metadata.getOrDefault("page_count", 0));
            
            workspaceService.saveDocument(document);
            
            job.setProgress(100);
            job.setStatus(Job.JobStatus.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            
            log.info("Job completed: {} - generated metadata for document", job.getId());
        } catch (Exception e) {
            log.error("Job failed: {} - {}", job.getId(), e.getMessage(), e);
            job.setStatus(Job.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(LocalDateTime.now());
        }
    }

    /**
     * Submit a job to generate PDF page thumbnails.
     */
    public Job queueGenerateThumbnails(String documentId) {
        Job job = new Job();
        job.setDocumentId(documentId);
        job.setType(Job.JobType.GENERATE_THUMBNAILS);
        
        Job submitted = submitJob(job);
        
        jobExecutor.submit(() -> executeGenerateThumbnails(submitted));
        
        return submitted;
    }

    /**
     * Execute job: Generate thumbnails for all pages.
     */
    private void executeGenerateThumbnails(Job job) {
        try {
            job.setStatus(Job.JobStatus.RUNNING);
            job.setStartedAt(LocalDateTime.now());
            job.setProgress(10);
            
            Optional<Document> docOpt = workspaceService.getDocument(job.getDocumentId());
            if (docOpt.isEmpty()) {
                throw new RuntimeException("Document not found: " + job.getDocumentId());
            }
            
            Document document = docOpt.get();
            Path pdfPath = workspaceService.getPdfPath(document.getId());
            
            log.info("Generating thumbnails for document: {} ({} pages)", 
                     document.getId(), document.getPageCount());
            
            if (!config.isThumbnailGenerationEnabled()) {
                log.info("Thumbnail generation is disabled");
                job.setStatus(Job.JobStatus.COMPLETED);
                job.setCompletedAt(LocalDateTime.now());
                return;
            }

            int pageCount = document.getPageCount();
            if (pageCount <= 0) {
                pageCount = tabulaWrapper.getPageCount(pdfPath);
                document.setPageCount(pageCount);
                workspaceService.saveDocument(document);
                log.info("Derived page_count={} from PDF for thumbnail job, documentId={}", pageCount, document.getId());
            }

            for (int page = 1; page <= pageCount; page++) {
                try {
                    tabulaWrapper.generatePageThumbnail(pdfPath, page, 0.5f);
                    job.setProgress(10 + (int) ((page / (float) pageCount) * 80));
                } catch (Exception e) {
                    log.warn("Failed to generate thumbnail for page {}", page, e);
                }
            }
            
            document.setThumbnailsGenerated(true);
            workspaceService.saveDocument(document);
            
            job.setProgress(100);
            job.setStatus(Job.JobStatus.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            
            log.info("Job completed: {} - generated thumbnails", job.getId());
        } catch (Exception e) {
            log.error("Job failed: {} - {}", job.getId(), e.getMessage(), e);
            job.setStatus(Job.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(LocalDateTime.now());
        }
    }

    /**
     * Submit a job to auto-detect tables on all pages.
     */
    public Job queueDetectTables(String documentId) {
        Job job = new Job();
        job.setDocumentId(documentId);
        job.setType(Job.JobType.DETECT_TABLES);
        
        Job submitted = submitJob(job);
        
        jobExecutor.submit(() -> executeDetectTables(submitted));
        
        return submitted;
    }

    /**
     * Execute job: Detect tables on all pages.
     */
    private void executeDetectTables(Job job) {
        try {
            job.setStatus(Job.JobStatus.RUNNING);
            job.setStartedAt(LocalDateTime.now());
            job.setProgress(10);
            
            Optional<Document> docOpt = workspaceService.getDocument(job.getDocumentId());
            if (docOpt.isEmpty()) {
                throw new RuntimeException("Document not found: " + job.getDocumentId());
            }
            
            Document document = docOpt.get();
            Path pdfPath = workspaceService.getPdfPath(document.getId());
            
            log.info("Detecting tables for document: {} ({} pages)", 
                     document.getId(), document.getPageCount());
            
            if (!config.isTableDetectionEnabled()) {
                log.info("Table detection is disabled");
                job.setStatus(Job.JobStatus.COMPLETED);
                job.setCompletedAt(LocalDateTime.now());
                return;
            }

            int pageCount = document.getPageCount();
            if (pageCount <= 0) {
                pageCount = tabulaWrapper.getPageCount(pdfPath);
                document.setPageCount(pageCount);
                workspaceService.saveDocument(document);
                log.info("Derived page_count={} from PDF for detect job, documentId={}", pageCount, document.getId());
            }

            Map<Integer, List<Map<String, Object>>> detectedTables = new LinkedHashMap<>();
            
            for (int page = 1; page <= pageCount; page++) {
                try {
                    List<Map<String, Object>> tables = tabulaWrapper.detectTablesOnPage(pdfPath, page);
                    if (!tables.isEmpty()) {
                        detectedTables.put(page, tables);
                    }
                    job.setProgress(10 + (int) ((page / (float) pageCount) * 80));
                } catch (Exception e) {
                    log.warn("Failed to detect tables on page {}", page, e);
                }
            }
            
            document.setTablesDetected(true);
            workspaceService.saveDocument(document);
            
            job.setProgress(100);
            job.setStatus(Job.JobStatus.COMPLETED);
            job.setResult(gson.toJson(detectedTables));
            job.setCompletedAt(LocalDateTime.now());
            
            log.info("Job completed: {} - detected tables on {} pages", job.getId(), detectedTables.size());
        } catch (Exception e) {
            log.error("Job failed: {} - {}", job.getId(), e.getMessage(), e);
            job.setStatus(Job.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(LocalDateTime.now());
        }
    }

    /**
     * Get current queue size (useful for monitoring).
     */
    public int getQueueSize() {
        return jobQueue.size();
    }

    /**
     * Get number of running jobs.
     */
    public int getRunningJobsCount() {
        return (int) jobQueue.values().stream()
            .filter(j -> j.getStatus() == Job.JobStatus.RUNNING)
            .count();
    }

    /**
     * Clear completed jobs from queue (for memory management).
     */
    public synchronized int clearCompletedJobs() {
        List<String> completedJobIds = jobQueue.entrySet().stream()
            .filter(e -> e.getValue().getStatus() == Job.JobStatus.COMPLETED 
                      || e.getValue().getStatus() == Job.JobStatus.FAILED)
            .map(Map.Entry::getKey)
            .toList();
        
        completedJobIds.forEach(jobQueue::remove);
        log.info("Cleared {} completed jobs from queue", completedJobIds.size());
        return completedJobIds.size();
    }

    /**
     * Shutdown the job executor gracefully.
     */
    public void shutdown() {
        if (jobExecutor != null) {
            log.info("Shutting down job executor...");
            jobExecutor.shutdown();
        }
    }

}
