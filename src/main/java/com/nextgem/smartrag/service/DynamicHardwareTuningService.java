package com.nextgem.smartrag.service;

import com.nextgem.smartrag.config.RagPipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enterprise Dynamic Hardware Tuning Service.
 * Manages runtime hardware resource allocation:
 * - RAM Capacity (4GB, 8GB, 16GB, 32GB, 64GB)
 * - CPU Cores allocation (2, 4, 8, 12, 16, 24, 32 Cores)
 * - Dynamic ThreadPoolExecutor worker thread scaling
 * - Dynamic Semaphore concurrency permit scaling
 * - Dynamic Memory Safety Ceilings
 */
@Service
public class DynamicHardwareTuningService {

    private static final Logger log = LoggerFactory.getLogger(DynamicHardwareTuningService.class);

    private final RagPipelineProperties properties;
    private final ThreadPoolExecutor executor;
    public static class ResizableSemaphore extends Semaphore {
        public ResizableSemaphore(int permits) {
            super(permits);
        }

        public void reduce(int permits) {
            super.reducePermits(permits);
        }
    }

    private final ResizableSemaphore semaphore;
    private final AtomicInteger currentMaxConcurrency;

    public DynamicHardwareTuningService(
            RagPipelineProperties properties,
            @Qualifier("pipelineExecutor") ThreadPoolExecutor executor
    ) {
        this.properties = properties;
        this.executor = executor;
        int initialCores = properties.getMaxConcurrency();
        this.currentMaxConcurrency = new AtomicInteger(initialCores);
        this.semaphore = new ResizableSemaphore(initialCores);

        log.info("DynamicHardwareTuningService initialized. Profile: {}GB RAM / {} Cores. Memory ceiling: {}MB",
                properties.getRamCapacityGb(), initialCores, properties.getMemorySafetyThresholdMb());
    }

    /**
     * Dynamically adjusts system tuning, thread pool, and concurrency permits at runtime.
     */
    public synchronized HardwareTuningProfile tuneHardware(int ramGb, int cores) {
        int safeRam = Math.max(2, Math.min(128, ramGb));
        int safeCores = Math.max(1, Math.min(64, cores));

        int oldCores = currentMaxConcurrency.get();
        log.info("Tuning hardware configuration: [RAM: {}GB -> {}GB, Cores: {} -> {}]",
                properties.getRamCapacityGb(), safeRam, oldCores, safeCores);

        // 1. Update properties
        properties.setRamCapacityGb(safeRam);
        properties.setMaxConcurrency(safeCores);
        // Clamp to 85% of JVM -Xmx so safety threshold never exceeds actual heap
        long rawCeiling = (long) (safeRam * 1024L * 0.75);
        properties.setMemorySafetyThresholdMb(rawCeiling);
        long newMemoryCeiling = properties.getEffectiveMemorySafetyCeilingMb();

        // 2. Adjust ThreadPoolExecutor (ensure core <= max before setting)
        if (safeCores * 2 > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(safeCores * 2);
            executor.setCorePoolSize(safeCores);
        } else {
            executor.setCorePoolSize(safeCores);
            executor.setMaximumPoolSize(safeCores * 2);
        }

        // 3. Adjust Semaphore permits
        int diff = safeCores - oldCores;
        if (diff > 0) {
            semaphore.release(diff);
        } else if (diff < 0) {
            semaphore.reduce(-diff);
        }
        currentMaxConcurrency.set(safeCores);

        log.info("Hardware tuning applied successfully. Active workers: {}, Semaphore permits: {}, Memory Ceiling: {}MB",
                executor.getCorePoolSize(), semaphore.availablePermits(), newMemoryCeiling);

        return getCurrentProfile();
    }

    public void acquirePermit() throws InterruptedException {
        semaphore.acquire();
    }

    public void releasePermit() {
        semaphore.release();
    }

    public HardwareTuningProfile getCurrentProfile() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long heapMax = runtime.maxMemory() / (1024 * 1024);

        return new HardwareTuningProfile(
                properties.getRamCapacityGb(),
                currentMaxConcurrency.get(),
                properties.getMemorySafetyThresholdMb(),
                executor.getActiveCount(),
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                runtime.availableProcessors(),
                heapUsed,
                heapMax,
                getAvailablePresets()
        );
    }

    public List<PresetProfile> getAvailablePresets() {
        return List.of(
                new PresetProfile("8GB_4CORE", "8 GB RAM / 4 Cores", 8, 4),
                new PresetProfile("8GB_8CORE", "8 GB RAM / 8 Cores (Default)", 8, 8),
                new PresetProfile("16GB_8CORE", "16 GB RAM / 8 Cores", 16, 8),
                new PresetProfile("16GB_16CORE", "16 GB RAM / 16 Cores (High Performance)", 16, 16),
                new PresetProfile("32GB_16CORE", "32 GB RAM / 16 Cores (Enterprise Scale)", 32, 16)
        );
    }

    public record HardwareTuningProfile(
            int ramCapacityGb,
            int allocatedCores,
            long memorySafetyCeilingMb,
            int activeThreads,
            int corePoolSize,
            int maxPoolSize,
            int systemProcessorsAvailable,
            long heapUsedMb,
            long heapMaxMb,
            List<PresetProfile> presets
    ) {}

    public record PresetProfile(
            String id,
            String label,
            int ramCapacityGb,
            int cores
    ) {}
}
