package com.tabula.controller;

import com.tabula.model.Job;
import com.tabula.service.JobExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API endpoints for background job monitoring.
 */
@RestController
@RequestMapping("/api/queue")
@CrossOrigin(origins = "*")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobExecutorService jobExecutorService;

    public JobController(JobExecutorService jobExecutorService) {
        this.jobExecutorService = jobExecutorService;
    }

    /**
     * GET /api/queue - Get all jobs in the queue.
     */
    @GetMapping
    public ResponseEntity<List<Job>> getAllJobs() {
        List<Job> jobs = jobExecutorService.getAllJobs();
        return ResponseEntity.ok(jobs);
    }

    /**
     * GET /api/queue/{jobId} - Get a specific job.
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<?> getJob(@PathVariable String jobId) {
        Optional<Job> job = jobExecutorService.getJob(jobId);
        
        if (job.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(job.get());
    }

    /**
     * GET /api/queue/document/{documentId} - Get jobs for a document.
     */
    @GetMapping("/document/{documentId}")
    public ResponseEntity<List<Job>> getJobsByDocument(@PathVariable String documentId) {
        List<Job> jobs = jobExecutorService.getJobsByDocumentId(documentId);
        return ResponseEntity.ok(jobs);
    }

    /**
     * GET /api/queue/stats - Get queue statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getQueueStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_jobs", jobExecutorService.getQueueSize());
        stats.put("running_jobs", jobExecutorService.getRunningJobsCount());
        
        List<Job> allJobs = jobExecutorService.getAllJobs();
        long completed = allJobs.stream()
            .filter(j -> j.getStatus() == Job.JobStatus.COMPLETED)
            .count();
        long failed = allJobs.stream()
            .filter(j -> j.getStatus() == Job.JobStatus.FAILED)
            .count();
        long queued = allJobs.stream()
            .filter(j -> j.getStatus() == Job.JobStatus.QUEUED)
            .count();
        
        stats.put("completed_jobs", completed);
        stats.put("failed_jobs", failed);
        stats.put("queued_jobs", queued);
        
        return ResponseEntity.ok(stats);
    }

    /**
     * POST /api/queue/cleanup - Clear completed jobs from memory.
     */
    @PostMapping("/cleanup")
    public ResponseEntity<?> cleanupCompletedJobs() {
        int cleaned = jobExecutorService.clearCompletedJobs();
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Cleanup completed");
        response.put("jobs_cleared", cleaned);
        
        log.info("Queue cleanup: {} completed jobs cleared", cleaned);
        
        return ResponseEntity.ok(response);
    }

}
