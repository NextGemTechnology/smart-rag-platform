package com.nextgem.smartrag.query;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * High-performance, lock-free query metrics tracker for monitoring
 * ~1,000 concurrent user traffic patterns, latency percentiles, and cache efficiency.
 */
@Component
public class QueryMetricsTracker {

    private final AtomicInteger activeQueries = new AtomicInteger(0);
    private final AtomicInteger queueDepth = new AtomicInteger(0);

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successfulRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    private final LongAdder throttledRequests = new LongAdder();
    private final LongAdder coalescedRequests = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();

    private final LongAdder totalFirstTokenLatencyMs = new LongAdder();
    private final LongAdder firstTokenCount = new LongAdder();

    // Bounded rolling reservoir of recent request latencies (ms) for percentile estimation
    private static final int RESERVOIR_CAPACITY = 2048;
    private final ConcurrentLinkedDeque<Long> recentLatencies = new ConcurrentLinkedDeque<>();

    public void incrementActive() {
        activeQueries.incrementAndGet();
    }

    public void decrementActive() {
        activeQueries.decrementAndGet();
    }

    public void incrementQueue() {
        queueDepth.incrementAndGet();
    }

    public void decrementQueue() {
        queueDepth.decrementAndGet();
    }

    public void recordSuccess(long latencyMs) {
        totalRequests.increment();
        successfulRequests.increment();
        recordLatency(latencyMs);
    }

    public void recordFailure(long latencyMs) {
        totalRequests.increment();
        failedRequests.increment();
        recordLatency(latencyMs);
    }

    public void recordThrottled() {
        totalRequests.increment();
        throttledRequests.increment();
    }

    public void recordCoalesced() {
        coalescedRequests.increment();
    }

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
    }

    public void recordFirstTokenLatency(long latencyMs) {
        totalFirstTokenLatencyMs.add(latencyMs);
        firstTokenCount.increment();
    }

    private void recordLatency(long latencyMs) {
        recentLatencies.addLast(latencyMs);
        while (recentLatencies.size() > RESERVOIR_CAPACITY) {
            recentLatencies.pollFirst();
        }
    }

    public QueryMetricsSnapshot getSnapshot() {
        Long[] samples = recentLatencies.toArray(new Long[0]);
        Arrays.sort(samples);

        long p50 = getPercentile(samples, 50);
        long p95 = getPercentile(samples, 95);
        long p99 = getPercentile(samples, 99);

        long hits = cacheHits.sum();
        long misses = cacheMisses.sum();
        double totalCacheOps = hits + misses;
        double hitRate = totalCacheOps > 0 ? (hits / totalCacheOps) * 100.0 : 0.0;

        long ftCount = firstTokenCount.sum();
        double avgFirstTokenMs = ftCount > 0 ? (double) totalFirstTokenLatencyMs.sum() / ftCount : 0.0;

        return new QueryMetricsSnapshot(
                activeQueries.get(),
                queueDepth.get(),
                totalRequests.sum(),
                successfulRequests.sum(),
                failedRequests.sum(),
                throttledRequests.sum(),
                coalescedRequests.sum(),
                hits,
                misses,
                Math.round(hitRate * 100.0) / 100.0,
                p50,
                p95,
                p99,
                Math.round(avgFirstTokenMs * 100.0) / 100.0
        );
    }

    private long getPercentile(Long[] sorted, int percentile) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil((percentile / 100.0) * sorted.length) - 1;
        index = Math.max(0, Math.min(sorted.length - 1, index));
        return sorted[index];
    }

    public record QueryMetricsSnapshot(
            int activeQueries,
            int queueDepth,
            long totalRequests,
            long successfulRequests,
            long failedRequests,
            long throttledRequests,
            long coalescedRequests,
            long cacheHits,
            long cacheMisses,
            double cacheHitRatePercent,
            long p50LatencyMs,
            long p95LatencyMs,
            long p99LatencyMs,
            double avgFirstTokenLatencyMs
    ) {}
}
