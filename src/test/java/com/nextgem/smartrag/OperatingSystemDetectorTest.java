package com.nextgem.smartrag;

import com.nextgem.smartrag.service.OperatingSystemDetector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class OperatingSystemDetectorTest {

    @Autowired
    private OperatingSystemDetector osDetector;

    @Test
    public void testSystemInfoDetection() {
        OperatingSystemDetector.SystemInfo info = osDetector.getSystemInfo();

        assertNotNull(info);
        assertNotNull(info.detectedOs());
        assertNotNull(info.activeMode());
        assertTrue(info.cpuCores() >= 1, "CPU cores should be at least 1");
        assertTrue(info.physicalMemoryMb() > 0, "Physical memory should be > 0");
        assertTrue(info.maxJvmHeapMb() > 0, "Max JVM heap should be > 0");
        assertNotNull(info.gpuInfo());
        assertNotNull(info.javaVersion());
        assertNotNull(info.osArch());
    }

    @Test
    public void testOsOverride() {
        osDetector.setOsOverride(OperatingSystemDetector.OsType.WINDOWS);
        assertEquals(OperatingSystemDetector.OsType.WINDOWS, osDetector.getSystemInfo().activeMode());

        osDetector.setOsOverride(OperatingSystemDetector.OsType.LINUX);
        assertEquals(OperatingSystemDetector.OsType.LINUX, osDetector.getSystemInfo().activeMode());

        // Reset to auto
        osDetector.setOsOverride(null);
        assertEquals(osDetector.getSystemInfo().detectedOs(), osDetector.getSystemInfo().activeMode());
    }
}
