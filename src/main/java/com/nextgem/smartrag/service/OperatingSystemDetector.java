package com.nextgem.smartrag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enterprise Cross-Platform Hardware and Operating System Detector.
 * Automatically inspects OS type, CPU architecture, RAM capacity, and GPU availability
 * across Windows, macOS, and Linux/Fallback environments.
 */
@Component
public class OperatingSystemDetector {

    private static final Logger log = LoggerFactory.getLogger(OperatingSystemDetector.class);

    public enum OsType {
        WINDOWS,
        MACOS,
        LINUX,
        FALLBACK
    }

    public record GpuInfo(boolean available, String name, String details) {
        public static final GpuInfo NONE = new GpuInfo(false, "None (CPU Only)", "No dedicated or integrated GPU acceleration detected");
    }

    public record SystemInfo(
            OsType detectedOs,
            OsType activeMode,
            int cpuCores,
            long physicalMemoryMb,
            long maxJvmHeapMb,
            GpuInfo gpuInfo,
            String javaVersion,
            String osArch,
            String osName
    ) {}

    private final AtomicReference<OsType> manualOverride = new AtomicReference<>(null);
    private final OsType nativeOs;
    private final GpuInfo cachedGpuInfo;

    public OperatingSystemDetector() {
        this.nativeOs = detectNativeOs();
        this.cachedGpuInfo = detectGpu(this.nativeOs);
        log.info("[OS-DETECTOR] Initialized: OS={}, Arch={}, Cores={}, GPU={}",
                nativeOs, System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(), cachedGpuInfo.name());
    }

    public SystemInfo getSystemInfo() {
        OsType active = (manualOverride.get() != null) ? manualOverride.get() : nativeOs;
        int cores = Runtime.getRuntime().availableProcessors();
        long physicalRamMb = getPhysicalMemorySizeMb();
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        return new SystemInfo(
                nativeOs,
                active,
                cores,
                physicalRamMb,
                maxHeapMb,
                cachedGpuInfo,
                System.getProperty("java.version"),
                System.getProperty("os.arch"),
                System.getProperty("os.name")
        );
    }

    public void setOsOverride(OsType override) {
        manualOverride.set(override);
        log.info("[OS-DETECTOR] Active OS mode set to: {}", (override != null ? override : "AUTO (" + nativeOs + ")"));
    }

    private OsType detectNativeOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return OsType.WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            return OsType.MACOS;
        } else if (os.contains("nux") || os.contains("nix") || os.contains("aix")) {
            return OsType.LINUX;
        }
        return OsType.FALLBACK;
    }

    private long getPhysicalMemorySizeMb() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                return sunBean.getTotalMemorySize() / (1024 * 1024);
            }
        } catch (Throwable ignored) {}
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    private GpuInfo detectGpu(OsType os) {
        try {
            switch (os) {
                case MACOS -> {
                    // Detect Apple Silicon Metal GPU or Discrete AMD
                    String arch = System.getProperty("os.arch", "").toLowerCase();
                    if (arch.contains("aarch64") || arch.contains("arm")) {
                        return new GpuInfo(true, "Apple Silicon GPU (Metal)", "Integrated Neural Engine & Metal GPU available");
                    }
                    String out = executeQuickCommand("system_profiler", "SPDisplaysDataType");
                    if (out != null && out.contains("Chipset Model:")) {
                        String name = extractLine(out, "Chipset Model:");
                        return new GpuInfo(true, name, "macOS Metal Display");
                    }
                }
                case LINUX -> {
                    String out = executeQuickCommand("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader");
                    if (out != null && !out.isBlank()) {
                        return new GpuInfo(true, out.trim(), "NVIDIA CUDA GPU");
                    }
                }
                case WINDOWS -> {
                    String out = executeQuickCommand("wmic", "path", "win32_VideoController", "get", "name");
                    if (out != null && out.lines().count() > 1) {
                        String name = out.lines().filter(l -> !l.isBlank() && !l.contains("Name")).findFirst().orElse("Windows GPU");
                        return new GpuInfo(true, name.trim(), "Windows Display Adapter");
                    }
                }
                default -> {}
            }
        } catch (Throwable t) {
            log.debug("[OS-DETECTOR] GPU detection exception: {}", t.getMessage());
        }
        return GpuInfo.NONE;
    }

    private String executeQuickCommand(String... args) {
        try {
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            boolean finished = process.waitFor(1200, TimeUnit.MILLISECONDS);
            if (finished && process.exitValue() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    return sb.toString();
                }
            } else {
                process.destroyForcibly();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractLine(String text, String prefix) {
        return text.lines()
                .filter(l -> l.contains(prefix))
                .map(l -> l.replace(prefix, "").trim())
                .findFirst()
                .orElse("GPU Device");
    }
}
