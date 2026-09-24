package com.nextgem.smartrag.query;

import com.nextgem.smartrag.service.RagGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency, Rate Limiting, and Backpressure Controller designed for ~1,000 Concurrent Users.
 * Key responsibilities:
 * 1. Bounded active permits: Prevents JVM heap/thread thrashing and CPU saturation.
 * 2. Bounded waiting queue: Rejects excess requests with backpressure (HTTP 429/503) instead of crashing.
 * 3. Token-bucket rate limiter per client IP.
 * 4. SingleFlight request coalescing: In-flight deduplication of concurrent identical queries.
 */
@Component
public class QueryConcurrencyGuard {

    private static final Logger log = LoggerFactory.getLogger(QueryConcurrencyGuard.class);

    private final Semaphore querySemaphore;
    private final int maxQueueDepth;
    private final long queueTimeoutMs;
    private final QueryMetricsTracker metricsTracker;

    private final AtomicInteger currentQueueDepth = new AtomicInteger(0);

    // In-Flight Request Deduplication (SingleFlight pattern)
    private final Map<String, CompletableFuture<RagGenerationService.RagAnswer>> inFlightQueries = new ConcurrentHashMap<>();

    // Per-IP Token Bucket Rate Limiting
    private static class TokenBucket {
        final double capacity;
        final double refillRatePerSec;
        double tokens;
        long lastRefillTimestamp;

        TokenBucket(double capacity, double refillRatePerSec) {
            this.capacity = capacity;
            this.refillRatePerSec = refillRatePerSec;
            this.tokens = capacity;
            this.lastRefillTimestamp = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            double elapsedSec = (now - lastRefillTimestamp) / 1000.0;
            tokens = Math.min(capacity, tokens + elapsedSec * refillRatePerSec);
            lastRefillTimestamp = now;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private final Map<String, TokenBucket> ipRateLimiters = new ConcurrentHashMap<>();

    public QueryConcurrencyGuard(
            @Value("${rag.query.max-concurrency:128}") int maxConcurrency,
            @Value("${rag.query.max-queue-depth:500}") int maxQueueDepth,
            @Value("${rag.query.queue-timeout-ms:5000}") long queueTimeoutMs,
            QueryMetricsTracker metricsTracker
    ) {
        this.querySemaphore = new Semaphore(maxConcurrency, true); // Fair FIFO queuing
        this.maxQueueDepth = maxQueueDepth;
        this.queueTimeoutMs = queueTimeoutMs;
        this.metricsTracker = metricsTracker;
        log.info("[CONCURRENCY-GUARD] Initialized with maxConcurrency: {}, maxQueueDepth: {}, queueTimeoutMs: {}ms",
                maxConcurrency, maxQueueDepth, queueTimeoutMs);
    }

    /**
     * Checks rate limits for the given client IP.
     * @return true if request is allowed, false if rate limited.
     */
    public boolean checkRateLimit(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            clientIp = "anonymous";
        }
        // Capacity: 30 burst tokens, Refill: 1.0 token/sec (60 requests/minute)
        TokenBucket bucket = ipRateLimiters.computeIfAbsent(clientIp, k -> new TokenBucket(30.0, 1.0));
        return bucket.tryConsume();
    }

    /**
     * Acquires an active execution permit with backpressure protection.
     * Throws QueryBackpressureException if the queue is full or timeout expires.
     */
    public boolean acquirePermit() throws InterruptedException {
        int queued = currentQueueDepth.get();
        if (queued >= maxQueueDepth) {
            metricsTracker.recordThrottled();
            throw new QueryBackpressureException("Query queue saturated (" + queued + "/" + maxQueueDepth + "). System under heavy load.");
        }

        currentQueueDepth.incrementAndGet();
        metricsTracker.incrementQueue();

        try {
            boolean acquired = querySemaphore.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                metricsTracker.recordThrottled();
                throw new QueryBackpressureException("Query permit wait timed out after " + queueTimeoutMs + "ms under concurrency backpressure.");
            }
            metricsTracker.incrementActive();
            return true;
        } finally {
            currentQueueDepth.decrementAndGet();
            metricsTracker.decrementQueue();
        }
    }

    public void releasePermit() {
        querySemaphore.release();
        metricsTracker.decrementActive();
    }

    /**
     * SingleFlight: Executes supplier or joins in-flight future if an identical query is already processing.
     */
    public CompletableFuture<RagGenerationService.RagAnswer> executeCoalesced(
            String deduplicationKey,
            Callable<RagGenerationService.RagAnswer> queryTask
    ) {
        CompletableFuture<RagGenerationService.RagAnswer> future = inFlightQueries.get(deduplicationKey);
        if (future != null) {
            metricsTracker.recordCoalesced();
            return future;
        }

        CompletableFuture<RagGenerationService.RagAnswer> newFuture = new CompletableFuture<>();
        CompletableFuture<RagGenerationService.RagAnswer> existing = inFlightQueries.putIfAbsent(deduplicationKey, newFuture);
        if (existing != null) {
            metricsTracker.recordCoalesced();
            return existing;
        }

        // Execute task
        CompletableFuture.runAsync(() -> {
            try {
                RagGenerationService.RagAnswer result = queryTask.call();
                newFuture.complete(result);
            } catch (Throwable t) {
                newFuture.completeExceptionally(t);
            } finally {
                inFlightQueries.remove(deduplicationKey);
            }
        });

        return newFuture;
    }

    public int getAvailablePermits() {
        return querySemaphore.availablePermits();
    }

    public int getCurrentQueueDepth() {
        return currentQueueDepth.get();
    }

    public static class QueryBackpressureException extends RuntimeException {
        public QueryBackpressureException(String message) {
            super(message);
        }
    }
}
