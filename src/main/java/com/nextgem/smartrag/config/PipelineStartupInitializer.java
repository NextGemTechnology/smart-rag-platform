package com.nextgem.smartrag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Startup initializer that creates all pipeline data directories
 * and logs the system readiness banner on application boot.
 */
@Component
public class PipelineStartupInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineStartupInitializer.class);

    private final RagPipelineProperties properties;

    public PipelineStartupInitializer(RagPipelineProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(String... args) throws Exception {
        // Create all pipeline directories
        Path[] dirs = {
                properties.getRawPdfPath(),
                properties.getStagingMdPath(),
                properties.getChunkedJsonlPath(),
                properties.getVectorStoragePath()
        };

        for (Path dir : dirs) {
            Files.createDirectories(dir);
            log.debug("Ensured pipeline directory exists: {}", dir);
        }

        Runtime runtime = Runtime.getRuntime();
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        int cores = runtime.availableProcessors();

        log.info("==================================================================");
        log.info("  RAG PIPELINE READY");
        log.info("==================================================================");
        log.info("  CPU Cores Available   : {}", cores);
        log.info("  Max JVM Heap          : {} MB", maxMb);
        log.info("  Concurrency Limit     : {}", properties.getMaxConcurrency());
        log.info("  Memory Safety Ceiling : {} MB", properties.getMemorySafetyThresholdMb());
        log.info("  Chunk Size / Overlap  : {} / {}", properties.getChunkSize(), properties.getChunkOverlap());
        log.info("  ChromaDB              : {}", properties.getChromaUrl());
        log.info("------------------------------------------------------------------");
        log.info("  Pipeline Directories:");
        log.info("    raw_pdfs     : {}", properties.getRawPdfPath());
        log.info("    staging_md   : {}", properties.getStagingMdPath());
        log.info("    chunked_jsonl: {}", properties.getChunkedJsonlPath());
        log.info("    vector_store : {}", properties.getVectorStoragePath());
        log.info("------------------------------------------------------------------");
        log.info("  REST Endpoints:");
        log.info("    POST /api/rag/upload-and-ingest          Upload files + full pipeline");
        log.info("    POST /api/rag/ingest?folderPath=<path>   Full pipeline from disk path");
        log.info("    POST /api/rag/parse-only                 Parse stage only");
        log.info("    POST /api/rag/chunk-only                 Chunk stage only");
        log.info("    POST /api/rag/vectorize-only             Embed + store stage only");
        log.info("    POST /api/rag/pipeline/resume            Resume last interrupted run");
        log.info("    POST /api/rag/ask?query=<text>&topK=5    RAG QA answer");
        log.info("    GET  /api/rag/search?query=<text>        Direct vector similarity search");
        log.info("    GET  /api/rag/pipeline-status            Live pipeline progress (poll)");
        log.info("    GET  /api/rag/system-info               OS/CPU/RAM/GPU/Java detection");
        log.info("    GET  /api/rag/resource-state            Heap state NORMAL/WARNING/CRITICAL");
        log.info("    GET  /api/rag/execution-plan            Adaptive thread/batch plan");
        log.info("    GET  /api/rag/inspect-file?filePath=..  File metadata (no modification)");
        log.info("    GET  /api/rag/jobs                       Ingestion audit history");
        log.info("    GET  /api/rag/vector-db/status          ChromaDB connection status");
        log.info("    GET  /api/rag/system-metrics             JVM heap telemetry");
        log.info("    POST /api/rag/hardware-tuning            Dynamic RAM/core tuning");
        log.info("    POST /api/rag/gc                         Force GC sweep");
        log.info("    POST /api/rag/os-override?os=WINDOWS    Manual OS mode override");
        log.info("==================================================================");
    }
}
