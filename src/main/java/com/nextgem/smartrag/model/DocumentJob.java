package com.nextgem.smartrag.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "document_jobs", indexes = {
        @Index(name = "idx_doc_checksum", columnList = "checksum"),
        @Index(name = "idx_doc_filename", columnList = "filename"),
        @Index(name = "idx_doc_run_id", columnList = "pipelineRunId"),
        @Index(name = "idx_doc_status", columnList = "status")
})
public class DocumentJob {

    public enum JobStatus {
        DISCOVERED, VALIDATED, PROCESSING, CHUNKED, EMBEDDED, STORED, COMPLETED, FAILED, SKIPPED;

        public static JobStatus fromString(String val) {
            if (val == null) return null;
            if (val.equalsIgnoreCase("SUCCESS")) return COMPLETED;
            try {
                return JobStatus.valueOf(val.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false, unique = true)
    private String checksum;

    private int totalPages;

    private int processedPages;

    private int totalChunks;

    private int embeddedChunks;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_status")
    private JobStatus jobStatus = JobStatus.DISCOVERED;

    @Column(name = "status")
    private String status = "DISCOVERED"; // Backwards-compatible string status

    private String pipelineRunId;

    private long executionTimeMs;

    @Column(length = 4000)
    private String errorMessage;

    @Column(length = 4000)
    private String failureReason;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime processedAt;

    public DocumentJob() {
        this.startedAt = LocalDateTime.now();
        this.processedAt = LocalDateTime.now();
    }

    public DocumentJob(String filename, String checksum, int totalPages, String status, long executionTimeMs, String errorMessage) {
        this.filename = filename;
        this.checksum = checksum;
        this.totalPages = totalPages;
        this.executionTimeMs = executionTimeMs;
        this.errorMessage = errorMessage;
        this.startedAt = LocalDateTime.now();
        this.processedAt = LocalDateTime.now();
        setStatus(status);
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public int getProcessedPages() { return processedPages; }
    public void setProcessedPages(int processedPages) { this.processedPages = processedPages; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public int getEmbeddedChunks() { return embeddedChunks; }
    public void setEmbeddedChunks(int embeddedChunks) { this.embeddedChunks = embeddedChunks; }

    public JobStatus getJobStatus() { return jobStatus; }
    public void setJobStatus(JobStatus jobStatus) {
        this.jobStatus = jobStatus;
        if (jobStatus == JobStatus.COMPLETED) {
            this.status = "SUCCESS";
        } else {
            this.status = (jobStatus != null) ? jobStatus.name() : null;
        }
    }

    public String getStatus() { return status; }
    public void setStatus(String status) {
        this.status = status;
        JobStatus parsed = JobStatus.fromString(status);
        if (parsed != null) {
            this.jobStatus = parsed;
        }
    }

    public String getPipelineRunId() { return pipelineRunId; }
    public void setPipelineRunId(String pipelineRunId) { this.pipelineRunId = pipelineRunId; }

    public long getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(long executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        if (this.failureReason == null) {
            this.failureReason = errorMessage;
        }
    }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
        if (this.errorMessage == null) {
            this.errorMessage = failureReason;
        }
    }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }
}
