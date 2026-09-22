package com.nextgem.smartrag.service;

import com.nextgem.smartrag.config.RagPipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enterprise Adaptive Performance Controller.
 * Computes optimal thread pool allocations, batch sizes, and execution strategies
 * based on live operating system and hardware characteristics.
 */
@Service
public class PerformanceController {

    private static final Logger log = LoggerFactory.getLogger(PerformanceController.class);

    public enum ComputeMode {
        AUTO,
        CPU,
        GPU
    }

    public record ExecutionPlan(
            int ioThreads,
            int cpuThreads,
            int embedThreads,
            int pageBatchSize,
            int chromaBatchSize,
            ComputeMode computeMode,
            String rationale
    ) {}

    private final OperatingSystemDetector osDetector;
    private final RagPipelineProperties properties;

    public PerformanceController(OperatingSystemDetector osDetector, RagPipelineProperties properties) {
        this.osDetector = osDetector;
        this.properties = properties;
    }

    /**
     * Calculates the optimal execution plan adapted to the host environment.
     */
    public ExecutionPlan getOptimalPlan() {
        OperatingSystemDetector.SystemInfo sysInfo = osDetector.getSystemInfo();
        int cores = Math.max(2, sysInfo.cpuCores());
        long physicalRamMb = sysInfo.physicalMemoryMb();

        // 1. I/O Threads: I/O bound work (reading PDF, writing markdown)
        int ioThreads = Math.min(cores, 8);

        // 2. CPU Threads: compute-bound text chunking and parsing
        int cpuThreads = cores;

        // 3. Embedding Threads:
        boolean hasGpu = sysInfo.gpuInfo().available();
        ComputeMode mode = hasGpu ? ComputeMode.AUTO : ComputeMode.CPU;
        int embedThreads = hasGpu ? Math.max(2, cores / 4) : Math.max(1, cores / 2);

        // 4. Page batch size: Scales with physical RAM to guarantee memory boundaries
        int pageBatch;
        if (physicalRamMb <= 4096) {
            pageBatch = 10;
        } else if (physicalRamMb <= 8192) {
            pageBatch = 20;
        } else if (physicalRamMb <= 16384) {
            pageBatch = 30;
        } else {
            pageBatch = 50;
        }

        // 5. Chroma batch size
        int chromaBatch = 64;

        String rationale = String.format(
                "Configured for %s on %d cores / %d MB RAM: [IO: %d, CPU: %d, Embed: %d, PageBatch: %d]",
                sysInfo.activeMode(), cores, physicalRamMb, ioThreads, cpuThreads, embedThreads, pageBatch
        );

        log.debug("[PERF-CONTROLLER] Optimal plan: {}", rationale);

        return new ExecutionPlan(ioThreads, cpuThreads, embedThreads, pageBatch, chromaBatch, mode, rationale);
    }
}
