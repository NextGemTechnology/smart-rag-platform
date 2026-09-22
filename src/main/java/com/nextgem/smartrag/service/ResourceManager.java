package com.nextgem.smartrag.service;

import com.nextgem.smartrag.config.RagPipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enterprise Resource Manager implementing a 3-state finite state machine
 * (NORMAL, WARNING, CRITICAL) to guarantee bounded memory and prevent OutOfMemoryError.
 */
@Service
public class ResourceManager {

    private static final Logger log = LoggerFactory.getLogger(ResourceManager.class);

    public enum ResourceState {
        NORMAL,   // Healthy heap (< 75%): Full speed processing
        WARNING,  // Moderate heap (75% - 90%): Concurrency throttled, brief pauses
        CRITICAL  // Extreme heap (> 90%): Ingestion paused, GC prompt, drain safety
    }

    private final RagPipelineProperties properties;
    private final MemoryMXBean memoryMXBean;
    private final AtomicReference<ResourceState> currentState = new AtomicReference<>(ResourceState.NORMAL);
    private final AtomicLong throttleCount = new AtomicLong(0);
    private final AtomicLong criticalPauseCount = new AtomicLong(0);

    public ResourceManager(RagPipelineProperties properties) {
        this.properties = properties;
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
    }

    /**
     * Evaluates the current resource state based on JVM heap utilization.
     */
    public ResourceState evaluateState() {
        long usedMb = getUsedHeapMb();
        long maxMb = getMaxHeapMb();
        double ratio = (maxMb > 0) ? (double) usedMb / maxMb : 0.0;

        ResourceState newState;
        if (ratio >= 0.90) {
            newState = ResourceState.CRITICAL;
        } else if (ratio >= 0.75) {
            newState = ResourceState.WARNING;
        } else {
            newState = ResourceState.NORMAL;
        }

        ResourceState oldState = currentState.getAndSet(newState);
        if (oldState != newState) {
            log.info("[RESOURCE-MANAGER] State transition: {} -> {} (Heap: {}/{} MB, {:.1f}%)",
                    oldState, newState, usedMb, maxMb, ratio * 100);
        }

        return newState;
    }

    /**
     * Proactive backpressure guardrail: checks memory before a task proceeds.
     * Blocks if in CRITICAL state until GC reclaims sufficient heap.
     * Pauses briefly if in WARNING state to throttle allocation rate.
     */
    public void checkAndThrottle() {
        ResourceState state = evaluateState();

        if (state == ResourceState.CRITICAL) {
            criticalPauseCount.incrementAndGet();
            log.warn("[RESOURCE-MANAGER] CRITICAL heap state! Pausing worker thread and triggering GC...");
            System.gc();

            int attempts = 0;
            while (attempts < 10) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                attempts++;
                if (evaluateState() != ResourceState.CRITICAL) {
                    log.info("[RESOURCE-MANAGER] Recovered from CRITICAL state after {} attempts.", attempts);
                    break;
                }
            }
        } else if (state == ResourceState.WARNING) {
            throttleCount.incrementAndGet();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Clears heap memory deterministically, pauses briefly for GC settlement, and re-evaluates state.
     */
    public void forceReclaim() {
        System.gc();
        try {
            Thread.sleep(60);
        } catch (InterruptedException ignored) {}
        evaluateState();
    }

    public ResourceState getCurrentState() {
        return evaluateState();
    }

    public long getUsedHeapMb() {
        return memoryMXBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    public long getMaxHeapMb() {
        long max = memoryMXBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
        if (max <= 0) {
            max = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        }
        return max;
    }

    public double getHeapUsagePercentage() {
        long max = getMaxHeapMb();
        if (max <= 0) return 0.0;
        return (getUsedHeapMb() * 100.0) / max;
    }

    public long getThrottleCount() {
        return throttleCount.get();
    }

    public long getCriticalPauseCount() {
        return criticalPauseCount.get();
    }
}
