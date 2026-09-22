package com.nextgem.smartrag;

import com.nextgem.smartrag.config.RagPipelineProperties;
import com.nextgem.smartrag.service.DynamicHardwareTuningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class DynamicHardwareTuningTest {

    @Autowired
    private DynamicHardwareTuningService tuningService;

    @Autowired
    private RagPipelineProperties properties;

    @Autowired
    private ThreadPoolExecutor executor;

    @Test
    @DisplayName("Verify dynamic reconfiguration for 16GB RAM and 16 CPU Cores")
    void testTuneHardwareTo16GbAnd16Cores() {
        // Initial state
        DynamicHardwareTuningService.HardwareTuningProfile initial = tuningService.getCurrentProfile();
        assertNotNull(initial);

        // Reconfigure to 16GB RAM and 16 Cores as requested by user
        DynamicHardwareTuningService.HardwareTuningProfile tuned = tuningService.tuneHardware(16, 16);

        assertEquals(16, tuned.ramCapacityGb(), "RAM capacity must be updated to 16 GB");
        assertEquals(16, tuned.allocatedCores(), "Cores must be updated to 16");
        assertEquals(12288L, tuned.memorySafetyCeilingMb(), "Memory ceiling must be 75% of 16GB (12288MB)");
        assertEquals(16, executor.getCorePoolSize(), "ThreadPoolExecutor core workers must scale to 16");
        assertEquals(32, executor.getMaximumPoolSize(), "ThreadPoolExecutor max workers must scale to 32");

        // Verify property updates
        assertEquals(16, properties.getRamCapacityGb());
        assertEquals(16, properties.getMaxConcurrency());
        assertEquals(12288L, properties.getMemorySafetyThresholdMb());

        // Reconfigure to 32GB RAM / 16 Cores
        DynamicHardwareTuningService.HardwareTuningProfile tuned32 = tuningService.tuneHardware(32, 16);
        assertEquals(32, tuned32.ramCapacityGb());
        assertEquals(24576L, tuned32.memorySafetyCeilingMb());

        // Reset back to 8GB / 8 Cores
        tuningService.tuneHardware(8, 8);
    }
}
