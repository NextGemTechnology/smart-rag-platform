package com.nextgem.smartrag.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resilient, low-overhead Circuit Breaker guarding external dependencies (ChromaDB / LLM).
 * When open, gracefully falls back to instant local disk retrieval and extractive synthesis,
 * preventing cascading worker pool exhaustion under heavy concurrency.
 */
@Component
public class QueryCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(QueryCircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int WINDOW_SIZE = 20;
    private static final double FAILURE_RATE_THRESHOLD = 0.50; // 50% failures trigger trip
    private static final long RESET_TIMEOUT_MS = 5000; // 5 seconds in OPEN before probing

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong lastStateChangeTime = new AtomicLong(System.currentTimeMillis());
    private final ConcurrentLinkedDeque<Boolean> recentCalls = new ConcurrentLinkedDeque<>();

    public boolean allowExecution() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (current == State.OPEN) {
            if (now - lastStateChangeTime.get() >= RESET_TIMEOUT_MS) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    lastStateChangeTime.set(now);
                    log.info("[CIRCUIT-BREAKER] Transitioned from OPEN to HALF_OPEN (probing)");
                    return true;
                }
            }
            return false;
        }

        // In HALF_OPEN, allow limited trial calls
        return true;
    }

    public void recordSuccess() {
        recordCall(true);
        if (state.get() == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                lastStateChangeTime.set(System.currentTimeMillis());
                recentCalls.clear();
                log.info("[CIRCUIT-BREAKER] Dependency recovered. Transitioned to CLOSED.");
            }
        }
    }

    public void recordFailure() {
        recordCall(false);
        State current = state.get();
        if (current == State.HALF_OPEN) {
            state.set(State.OPEN);
            lastStateChangeTime.set(System.currentTimeMillis());
            log.warn("[CIRCUIT-BREAKER] Probe failed in HALF_OPEN. Transitioned back to OPEN.");
            return;
        }

        if (current == State.CLOSED && recentCalls.size() >= WINDOW_SIZE / 2) {
            long failures = recentCalls.stream().filter(success -> !success).count();
            double rate = (double) failures / recentCalls.size();
            if (rate >= FAILURE_RATE_THRESHOLD) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    lastStateChangeTime.set(System.currentTimeMillis());
                    log.error("[CIRCUIT-BREAKER] High failure rate ({}/{} = {}%). Tripping circuit to OPEN for {}ms.",
                            failures, recentCalls.size(), Math.round(rate * 100), RESET_TIMEOUT_MS);
                }
            }
        }
    }

    private void recordCall(boolean success) {
        recentCalls.addLast(success);
        while (recentCalls.size() > WINDOW_SIZE) {
            recentCalls.pollFirst();
        }
    }

    public State getState() {
        return state.get();
    }
}
