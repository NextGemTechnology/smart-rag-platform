package com.nextgem.smartrag;

import com.nextgem.smartrag.service.ResourceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ResourceManagerTest {

    @Autowired
    private ResourceManager resourceManager;

    @Test
    public void testResourceStateEvaluation() {
        ResourceManager.ResourceState state = resourceManager.getCurrentState();
        assertNotNull(state);

        long used = resourceManager.getUsedHeapMb();
        long max = resourceManager.getMaxHeapMb();
        double pct = resourceManager.getHeapUsagePercentage();

        assertTrue(used >= 0);
        assertTrue(max > 0);
        assertTrue(pct >= 0.0 && pct <= 100.0);
    }

    @Test
    public void testForceReclaim() {
        long before = resourceManager.getUsedHeapMb();
        resourceManager.forceReclaim();
        long after = resourceManager.getUsedHeapMb();
        assertTrue(after <= before + 50, "Heap after reclaim should not experience runaway growth");
    }

    @Test
    public void testCheckAndThrottleDoesNotThrow() {
        assertDoesNotThrow(() -> resourceManager.checkAndThrottle());
    }
}
