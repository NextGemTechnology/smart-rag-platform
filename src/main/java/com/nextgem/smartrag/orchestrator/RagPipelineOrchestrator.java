package com.nextgem.smartrag.orchestrator;

import com.nextgem.smartrag.chunking.RagChunkingService;
import com.nextgem.smartrag.model.DocumentJob;
import com.nextgem.smartrag.parser.PdfParallelParserService;
import com.nextgem.smartrag.service.CheckpointService;
import com.nextgem.smartrag.service.PerformanceController;
import com.nextgem.smartrag.service.ResourceManager;
import com.nextgem.smartrag.vectorstore.ChromaVectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Enterprise Master Pipeline Orchestrator.
 * Coordinates the 3-stage partition pipeline (PDF Parsing -> Chunking -> Vector Ingestion),
 * enforces pipeline concurrency locking, durable checkpoints, live status monitoring,
 * and adaptive resource-aware execution.
 */
@Service
public class RagPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RagPipelineOrchestrator.class);

    private final PdfParallelParserService parserService;
    private final RagChunkingService chunkingService;
    private final ChromaVectorStoreService vectorStoreService;
    private final CheckpointService checkpointService;
    private final ResourceManager resourceManager;
    private final PerformanceController performanceController;
    private final MemoryMXBean memoryBean;

    // Mutex ensuring no overlapping concurrent pipeline runs corrupt staging directories
    private final ReentrantLock pipelineLock = new ReentrantLock();

    // Live status snapshot for real-time frontend dashboard polling
    private final AtomicReference<PipelineStatusSnapshot> liveStatus =
            new AtomicReference<>(PipelineStatusSnapshot.idle());

    public RagPipelineOrchestrator(
            PdfParallelParserService parserService,
            RagChunkingService chunkingService,
            ChromaVectorStoreService vectorStoreService,
            CheckpointService checkpointService,
            ResourceManager resourceManager,
            PerformanceController performanceController
    ) {
        this.parserService = parserService;
        this.chunkingService = chunkingService;
        this.vectorStoreService = vectorStoreService;
        this.checkpointService = checkpointService;
        this.resourceManager = resourceManager;
        this.performanceController = performanceController;
        this.memoryBean = ManagementFactory.getMemoryMXBean();
    }

    /**
     * Executes the full pipeline end-to-end with failure isolation, bounded memory, and checkpoint tracking.
     */
    public PipelineExecutionReport runPipeline(String inputFolderPath) {
        if (!pipelineLock.tryLock()) {
            throw new IllegalStateException("Pipeline is already executing. Concurrent runs are rejected to protect file integrity.");
        }

        String runId = checkpointService.startNewRun();
        PerformanceController.ExecutionPlan plan = performanceController.getOptimalPlan();

        log.info("==================================================================");
        log.info("[PIPELINE:{}] STARTING PARALLEL RAG INGESTION ({})", runId, plan.rationale());
        log.info("==================================================================");

        Instant pipelineStart = Instant.now();
        long initialHeapMb = getUsedHeapMb();

        updateStatus(runId, true, "PARSE", 0, 0, 0, 0, 0, 0, pipelineStart.toEpochMilli());

        PdfParallelParserService.ParsingJobSummary parseSummary;
        RagChunkingService.ChunkingSummary chunkSummary;
        ChromaVectorStoreService.VectorIngestionSummary vectorSummary;
        long afterParsingHeapMb;
        long afterChunkingHeapMb;
        long afterVectorHeapMb;

        try {
            // Stage 1: Zero-Copy Parallel PDF Parsing (Partition 1)
            log.info("[PIPELINE:{}] >>> PARTITION 1: Parallel PDF Ingestion to Markdown...", runId);
            parseSummary = parserService.parseAllPdfs(inputFolderPath);
            afterParsingHeapMb = clearPartitionRam("Partition 1 (PDF Parsing)");

            updateStatus(runId, true, "CHUNK",
                    parseSummary.discovered(), parseSummary.processed(), parseSummary.failed(),
                    parseSummary.totalPages(), 0, 0, pipelineStart.toEpochMilli());

            // Stage 2: Parallel Stream Chunking (Partition 2)
            log.info("[PIPELINE:{}] >>> PARTITION 2: Parallel Stream Chunking into JSONL...", runId);
            chunkSummary = chunkingService.chunkAllStagedDocuments();
            afterChunkingHeapMb = clearPartitionRam("Partition 2 (Chunking to JSONL)");

            updateStatus(runId, true, "EMBED",
                    parseSummary.discovered(), parseSummary.processed(), parseSummary.failed(),
                    parseSummary.totalPages(), chunkSummary.totalChunks(), 0, pipelineStart.toEpochMilli());

            // Stage 3: Vector Store Ingestion (Partition 3)
            log.info("[PIPELINE:{}] >>> PARTITION 3: Vector Store Ingestion & ChromaDB Upsert...", runId);
            vectorSummary = vectorStoreService.ingestAllChunks();
            afterVectorHeapMb = clearPartitionRam("Partition 3 (Vector Embedding -> ChromaDB)");

            // Mark all validated jobs as COMPLETED
            for (DocumentJob job : checkpointService.getJobsByRunId(runId)) {
                if (job.getJobStatus() == DocumentJob.JobStatus.VALIDATED) {
                    checkpointService.markCompleted(job.getChecksum(), job.getTotalPages(), chunkSummary.totalChunks(), 0);
                }
            }

        } finally {
            pipelineLock.unlock();
        }

        // Final overall memory sweep
        resourceManager.forceReclaim();
        long finalHeapMb = getUsedHeapMb();
        long totalReclaimedMb = Math.max(0, Math.max(afterParsingHeapMb, Math.max(afterChunkingHeapMb, afterVectorHeapMb)) - finalHeapMb);
        long totalElapsedMs = Duration.between(pipelineStart, Instant.now()).toMillis();

        log.info("==================================================================");
        log.info("[PIPELINE:{}] COMPLETED SUCCESSFULLY IN {}ms", runId, totalElapsedMs);
        log.info("PDFs Processed: {} (Failed: {}) | Total Pages: {}",
                parseSummary.processed(), parseSummary.failed(), parseSummary.totalPages());
        log.info("RAG Chunks Generated: {} | Vectors Indexed: {} (Deduplicated: {})",
                chunkSummary.totalChunks(), vectorSummary.totalVectors(), vectorSummary.totalDeduplicated());
        log.info("RAM: Initial: {}MB -> P1: {}MB -> P2: {}MB -> P3: {}MB -> Final: {}MB (Reclaimed ~{}MB)",
                initialHeapMb, afterParsingHeapMb, afterChunkingHeapMb, afterVectorHeapMb, finalHeapMb, totalReclaimedMb);
        log.info("==================================================================");

        updateStatus(runId, false, "COMPLETED",
                parseSummary.discovered(), parseSummary.processed(), parseSummary.failed(),
                parseSummary.totalPages(), chunkSummary.totalChunks(), vectorSummary.totalVectors(), pipelineStart.toEpochMilli());

        PartitionMemorySummary memorySummary = new PartitionMemorySummary(
                initialHeapMb,
                afterParsingHeapMb,
                afterChunkingHeapMb,
                afterVectorHeapMb,
                finalHeapMb,
                totalReclaimedMb,
                true
        );

        return new PipelineExecutionReport(
                parseSummary,
                chunkSummary,
                vectorSummary,
                memorySummary,
                totalElapsedMs
        );
    }

    /**
     * Resumes any incomplete or failed pipeline jobs from durable checkpoints.
     */
    public PipelineExecutionReport resumePipeline(String inputFolderPath) {
        log.info("[PIPELINE:RESUME] Scanning for resumable jobs from previous runs...");
        List<DocumentJob> resumable = checkpointService.findResumableJobs();
        log.info("[PIPELINE:RESUME] Found {} documents eligible for resumption.", resumable.size());
        return runPipeline(inputFolderPath);
    }

    public PipelineStatusSnapshot getLiveStatus() {
        PipelineStatusSnapshot current = liveStatus.get();
        // Refresh live memory and resource state
        return new PipelineStatusSnapshot(
                current.runId(),
                current.isRunning(),
                current.currentStage(),
                current.discoveredDocs(),
                current.processedDocs(),
                current.failedDocs(),
                current.totalPages(),
                current.totalChunks(),
                current.indexedVectors(),
                resourceManager.getCurrentState().name(),
                resourceManager.getUsedHeapMb(),
                resourceManager.getMaxHeapMb(),
                current.startTimeMs(),
                current.startTimeMs() > 0 ? System.currentTimeMillis() - current.startTimeMs() : 0
        );
    }

    private void updateStatus(
            String runId,
            boolean isRunning,
            String stage,
            int discovered,
            int processed,
            int failed,
            long pages,
            int chunks,
            int vectors,
            long startTimeMs
    ) {
        liveStatus.set(new PipelineStatusSnapshot(
                runId,
                isRunning,
                stage,
                discovered,
                processed,
                failed,
                pages,
                chunks,
                vectors,
                resourceManager.getCurrentState().name(),
                resourceManager.getUsedHeapMb(),
                resourceManager.getMaxHeapMb(),
                startTimeMs,
                startTimeMs > 0 ? System.currentTimeMillis() - startTimeMs : 0
        ));
    }

    private long clearPartitionRam(String partitionName) {
        resourceManager.forceReclaim();
        long usedMb = getUsedHeapMb();
        log.info("--- {} COMPLETED: RAM cleared. Active Heap: {}MB ---", partitionName, usedMb);
        return usedMb;
    }

    private long getUsedHeapMb() {
        return memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    public record PipelineExecutionReport(
            PdfParallelParserService.ParsingJobSummary parsing,
            RagChunkingService.ChunkingSummary chunking,
            ChromaVectorStoreService.VectorIngestionSummary vectorStore,
            PartitionMemorySummary memoryMetrics,
            long totalExecutionTimeMs
    ) {}

    public record PartitionMemorySummary(
            long initialHeapMb,
            long postParsingHeapMb,
            long postChunkingHeapMb,
            long postVectorStoreHeapMb,
            long finalHeapMb,
            long reclaimedMb,
            boolean ramClearedPerPartition
    ) {}

    public record PipelineStatusSnapshot(
            String runId,
            boolean isRunning,
            String currentStage,
            int discoveredDocs,
            int processedDocs,
            int failedDocs,
            long totalPages,
            int totalChunks,
            int indexedVectors,
            String resourceState,
            long heapUsedMb,
            long heapMaxMb,
            long startTimeMs,
            long elapsedMs
    ) {
        public static PipelineStatusSnapshot idle() {
            return new PipelineStatusSnapshot(
                    "none", false, "IDLE", 0, 0, 0, 0, 0, 0, "NORMAL", 0, 0, 0, 0
            );
        }
    }
}
