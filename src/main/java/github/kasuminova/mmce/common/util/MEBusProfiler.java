package github.kasuminova.mmce.common.util;

import hellfirepvp.modularmachinery.ModularMachinery;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized profiler for ME Item buses.
 * Aggregates performance statistics from all Input and Output buses.
 * Reports averages over 5s, 30s, and 60s intervals every minute.
 * Saves results to CSV file for easy plotting.
 *
 * ENHANCED VERSION: Now tracks AE2 operation counts, success rates, and items transferred!
 */
public class MEBusProfiler {

    // Singleton instance
    private static final MEBusProfiler INSTANCE = new MEBusProfiler();

    // Check system property to enable profiling - RUNTIME METHOD, NOT CACHED
    public static boolean isProfilingEnabled() {
        String prop = System.getProperty("mmce.profile.meitembus");
        return "true".equalsIgnoreCase(prop);
    }

    // Separate tracking for Input and Output buses
    private final BusTypeStats inputBusStats = new BusTypeStats("MEItemInputBus");
    private final BusTypeStats outputBusStats = new BusTypeStats("MEItemOutputBus");

    // Timing for periodic reports (report every 60 seconds = 1200 ticks at 20 TPS)
    private long lastReportTime = 0;
    private static final long REPORT_INTERVAL_MS = 1_000; // 1 seconds

    // CSV file writer
    private PrintWriter csvWriter = null;
    private final File csvFile;
    private long reportNumber = 0;

    private MEBusProfiler() {
        System.out.println("========================================");
        System.out.println("MEBusProfiler constructor ENTERED!");
        System.out.println("isProfilingEnabled() = " + isProfilingEnabled());
        System.out.println("System property = " + System.getProperty("mmce.profile.meitembus"));
        System.out.println("========================================");

        File csvFileTemp = null;

        if (isProfilingEnabled()) {
            ModularMachinery.log.info("===========================================");
            ModularMachinery.log.info("MEBusProfiler ENABLED with ENHANCED TRACKING!");
            ModularMachinery.log.info("Tracking: Timing + AE2 Operations + Success Rates");
            ModularMachinery.log.info("Aggregate reports will appear every 60 seconds");
            ModularMachinery.log.info("Add -Dmmce.profile.meitembus=true to enable");

            // Create CSV file in config directory (known location)
            try {
                String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());

                // DEBUG: Log current working directory
                File currentDir = new File(".").getAbsoluteFile();
                ModularMachinery.log.info("Current working directory: " + currentDir.getAbsolutePath());

                // Try to save in config directory first, fall back to current directory
                File configDir = new File("config");
                File csvDir;

                ModularMachinery.log.info("Config directory path: " + configDir.getAbsolutePath());
                ModularMachinery.log.info("Config directory exists: " + configDir.exists());
                ModularMachinery.log.info("Config directory is directory: " + configDir.isDirectory());

                if (configDir.exists() && configDir.isDirectory()) {
                    // Save in config/mmce_profiling/ directory
                    csvDir = new File(configDir, "mmce_profiling");
                    ModularMachinery.log.info("Creating mmce_profiling subdirectory at: " + csvDir.getAbsolutePath());

                    if (!csvDir.exists()) {
                        boolean created = csvDir.mkdirs();
                        ModularMachinery.log.info("Directory creation result: " + created);
                        ModularMachinery.log.info("Directory exists after mkdirs: " + csvDir.exists());
                    } else {
                        ModularMachinery.log.info("Directory already exists");
                    }

                    ModularMachinery.log.info("Directory is writable: " + csvDir.canWrite());
                    ModularMachinery.log.info("Saving CSV to config/mmce_profiling/ directory");
                } else {
                    // Fall back to current directory
                    csvDir = new File(".");
                    ModularMachinery.log.info("Config directory not found, saving CSV to current directory");
                    ModularMachinery.log.info("Current directory is writable: " + csvDir.canWrite());
                }

                // DEBUG: Test write permissions before creating actual CSV
                try {
                    File testFile = new File(csvDir, "test_write_permission.txt");
                    ModularMachinery.log.info("Testing write permissions with: " + testFile.getAbsolutePath());
                    PrintWriter testWriter = new PrintWriter(testFile);
                    testWriter.println("Test write successful");
                    testWriter.close();
                    boolean deleted = testFile.delete();
                    ModularMachinery.log.info("Write permission test: SUCCESS (test file deleted: " + deleted + ")");
                } catch (IOException e) {
                    ModularMachinery.log.error("Write permission test: FAILED! Cannot write to directory!", e);
                }

                csvFileTemp = new File(csvDir, "mmce_profiling_enhanced_" + timestamp + ".csv");
                ModularMachinery.log.info("Creating CSV file at: " + csvFileTemp.getAbsolutePath());

                csvWriter = new PrintWriter(new FileWriter(csvFileTemp, false));
                ModularMachinery.log.info("PrintWriter created successfully");

                // Write CSV header with ENHANCED METRICS
                csvWriter.println("Timestamp,ReportNumber,BusType,TotalTicks," +
                        "Avg1s_TickTime_us,Avg1s_ProcessWorkTime_us,Avg1s_GetSlotsTime_us," +
                        "Avg1s_SlotsChecked,Avg1s_AE2Operations,Avg1s_SuccessfulOps,Avg1s_ItemsTransferred,Avg1s_EfficiencyRatio," +
                        "Avg5s_TickTime_us,Avg5s_ProcessWorkTime_us,Avg5s_GetSlotsTime_us," +
                        "Avg5s_SlotsChecked,Avg5s_AE2Operations,Avg5s_SuccessfulOps,Avg5s_ItemsTransferred,Avg5s_EfficiencyRatio," +
                        "Avg30s_TickTime_us,Avg30s_ProcessWorkTime_us,Avg30s_GetSlotsTime_us," +
                        "Avg30s_SlotsChecked,Avg30s_AE2Operations,Avg30s_SuccessfulOps,Avg30s_ItemsTransferred,Avg30s_EfficiencyRatio," +
                        "Avg60s_TickTime_us,Avg60s_ProcessWorkTime_us,Avg60s_GetSlotsTime_us," +
                        "Avg60s_SlotsChecked,Avg60s_AE2Operations,Avg60s_SuccessfulOps,Avg60s_ItemsTransferred,Avg60s_EfficiencyRatio");
                csvWriter.flush();
                ModularMachinery.log.info("CSV header written and flushed");

                // DEBUG: Verify file exists and has content
                if (csvFileTemp.exists()) {
                    ModularMachinery.log.info("CSV file verified to exist!");
                    ModularMachinery.log.info("CSV file size: " + csvFileTemp.length() + " bytes");
                    ModularMachinery.log.info("CSV file can read: " + csvFileTemp.canRead());
                    ModularMachinery.log.info("CSV file can write: " + csvFileTemp.canWrite());
                } else {
                    ModularMachinery.log.error("WARNING: CSV file does NOT exist after creation!");
                }

                ModularMachinery.log.info("CSV output file: " + csvFileTemp.getAbsolutePath());

                // Add shutdown hook to ensure CSV is properly closed
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        if (csvWriter != null) {
                            ModularMachinery.log.info("Shutdown hook: Closing CSV file...");
                            csvWriter.flush();
                            csvWriter.close();
                            ModularMachinery.log.info("Shutdown hook: CSV file closed successfully");
                        }
                    } catch (Exception e) {
                        ModularMachinery.log.error("Shutdown hook: Error closing CSV file", e);
                    }
                }, "MEBusProfiler-Shutdown"));
                ModularMachinery.log.info("Shutdown hook registered for CSV file");

            } catch (IOException e) {
                ModularMachinery.log.error("Failed to create CSV file for profiling", e);
                ModularMachinery.log.error("Exception type: " + e.getClass().getName());
                ModularMachinery.log.error("Exception message: " + e.getMessage());
                csvFileTemp = null;
            }

            ModularMachinery.log.info("===========================================");
        } else {
            ModularMachinery.log.info("MEBusProfiler: Profiling is DISABLED");
            ModularMachinery.log.info("To enable, add JVM argument: -Dmmce.profile.meitembus=true");
        }

        this.csvFile = csvFileTemp;
    }

    public static MEBusProfiler getInstance() {
        return INSTANCE;
    }

    /**
     * Record a tick for an Input bus (basic timing only)
     */
    public void recordInputBusTick(long tickTimeNanos, long processWorkTimeNanos, long getSlotsTimeNanos) {
        if (!isProfilingEnabled()) return;
        inputBusStats.recordTick(tickTimeNanos, processWorkTimeNanos, getSlotsTimeNanos);
        checkAndReport();
    }

    /**
     * Record enhanced metrics for an Input bus (AE2 operations, success, items)
     */
    public void recordInputBusEnhancedMetrics(int slotsChecked, int ae2OpsCount, int successfulOps, long itemsTransferred) {
        if (!isProfilingEnabled()) return;
        inputBusStats.recordEnhancedMetrics(slotsChecked, ae2OpsCount, successfulOps, itemsTransferred);
    }

    /**
     * Record a tick for an Output bus (basic timing only)
     */
    public void recordOutputBusTick(long tickTimeNanos, long processWorkTimeNanos, long getSlotsTimeNanos) {
        if (!isProfilingEnabled()) return;
        outputBusStats.recordTick(tickTimeNanos, processWorkTimeNanos, getSlotsTimeNanos);
        checkAndReport();
    }

    /**
     * Record enhanced metrics for an Output bus (AE2 operations, success, items)
     */
    public void recordOutputBusEnhancedMetrics(int slotsChecked, int ae2OpsCount, int successfulOps, long itemsTransferred) {
        if (!isProfilingEnabled()) return;
        outputBusStats.recordEnhancedMetrics(slotsChecked, ae2OpsCount, successfulOps, itemsTransferred);
    }

    /**
     * Check if it's time to report and generate the report if needed
     */
    private synchronized void checkAndReport() {
        long currentTime = System.currentTimeMillis();
        if (lastReportTime == 0) {
            lastReportTime = currentTime;
            return;
        }

        if (currentTime - lastReportTime >= REPORT_INTERVAL_MS) {
            generateReport();
            lastReportTime = currentTime;
        }
    }

    /**
     * Generate and log the aggregate performance report
     */
    private void generateReport() {
        reportNumber++;
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        ModularMachinery.log.info("========================================");
        ModularMachinery.log.info("ME Item Bus ENHANCED Performance Report");
        ModularMachinery.log.info("Report #" + reportNumber + " at " + timestamp);
        ModularMachinery.log.info("========================================");

        inputBusStats.logReport();
        ModularMachinery.log.info("----------------------------------------");
        outputBusStats.logReport();

        ModularMachinery.log.info("========================================");

        // Write to CSV file
        if (csvWriter != null) {
            try {
                inputBusStats.writeCSV(csvWriter, timestamp, reportNumber);
                outputBusStats.writeCSV(csvWriter, timestamp, reportNumber);
                csvWriter.flush();
                ModularMachinery.log.info("CSV data written and flushed for report #" + reportNumber);
            } catch (Exception e) {
                ModularMachinery.log.error("Failed to write to CSV file", e);
            }
        } else {
            ModularMachinery.log.warn("CSV writer is null - cannot write report to file!");
        }
    }

    /**
     * Statistics tracker for a specific bus type (Input or Output)
     * ENHANCED VERSION: Now tracks AE2 operations and success metrics!
     */
    private static class BusTypeStats {
        private final String busTypeName;

        // Ring buffers for time windows
        private final EnhancedRingBuffer samples1s = new EnhancedRingBuffer(20);    // 1s at 20 TPS
        private final EnhancedRingBuffer samples5s = new EnhancedRingBuffer(100);   // 5s at 20 TPS
        private final EnhancedRingBuffer samples30s = new EnhancedRingBuffer(600);  // 30s at 20 TPS
        private final EnhancedRingBuffer samples60s = new EnhancedRingBuffer(1200); // 60s at 20 TPS

        // Total counters for all-time stats
        private final AtomicLong totalTicks = new AtomicLong(0);
        private final AtomicLong totalTickTime = new AtomicLong(0);
        private final AtomicLong totalProcessWorkTime = new AtomicLong(0);
        private final AtomicLong totalGetSlotsTime = new AtomicLong(0);

        BusTypeStats(String busTypeName) {
            this.busTypeName = busTypeName;
        }

        void recordTick(long tickTimeNanos, long processWorkTimeNanos, long getSlotsTimeNanos) {
            EnhancedTickSample sample = new EnhancedTickSample(
                    tickTimeNanos, processWorkTimeNanos, getSlotsTimeNanos,
                    0, 0, 0, 0  // Enhanced metrics will be added separately
            );

            // Add to ring buffers
            samples1s.add(sample);
            samples5s.add(sample);
            samples30s.add(sample);
            samples60s.add(sample);

            // Update totals
            totalTicks.incrementAndGet();
            totalTickTime.addAndGet(tickTimeNanos);
            totalProcessWorkTime.addAndGet(processWorkTimeNanos);
            totalGetSlotsTime.addAndGet(getSlotsTimeNanos);
        }

        void recordEnhancedMetrics(int slotsChecked, int ae2OpsCount, int successfulOps, long itemsTransferred) {
            // Update the most recent sample with enhanced metrics
            samples1s.updateLastSampleMetrics(slotsChecked, ae2OpsCount, successfulOps, itemsTransferred);
            samples5s.updateLastSampleMetrics(slotsChecked, ae2OpsCount, successfulOps, itemsTransferred);
            samples30s.updateLastSampleMetrics(slotsChecked, ae2OpsCount, successfulOps, itemsTransferred);
            samples60s.updateLastSampleMetrics(slotsChecked, ae2OpsCount, successfulOps, itemsTransferred);
        }

        void logReport() {
            long ticks = totalTicks.get();
            if (ticks == 0) {
                ModularMachinery.log.info(busTypeName + ": No ticks recorded");
                return;
            }

            ModularMachinery.log.info(busTypeName + " Statistics:");
            ModularMachinery.log.info(String.format("  Total Ticks: %,d", ticks));

            // 1 second average
            EnhancedTickSample avg1s = samples1s.getAverage();
            ModularMachinery.log.info("  Last 1 second average:");
            ModularMachinery.log.info(String.format("    Tick Time:             %10.2f µs", avg1s.tickTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    ProcessWork Time:      %10.2f µs", avg1s.processWorkTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    GetSlots Time:         %10.2f µs", avg1s.getSlotsTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    Slots Checked:         %10.2f per tick", avg1s.slotsChecked));
            ModularMachinery.log.info(String.format("    AE2 Operations:        %10.2f per tick", avg1s.ae2Operations));
            ModularMachinery.log.info(String.format("    Successful Ops:        %10.2f per tick", avg1s.successfulOps));
            ModularMachinery.log.info(String.format("    Items Transferred:     %10.2f per tick", avg1s.itemsTransferred));
            ModularMachinery.log.info(String.format("    Efficiency Ratio:      %10.1f%%", avg1s.getEfficiencyPercent()));

            // 5 second average
            EnhancedTickSample avg5s = samples5s.getAverage();
            ModularMachinery.log.info("  Last 5 seconds average:");
            ModularMachinery.log.info(String.format("    Tick Time:             %10.2f µs", avg5s.tickTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    ProcessWork Time:      %10.2f µs", avg5s.processWorkTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    GetSlots Time:         %10.2f µs", avg5s.getSlotsTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    Slots Checked:         %10.2f per tick", avg5s.slotsChecked));
            ModularMachinery.log.info(String.format("    AE2 Operations:        %10.2f per tick", avg5s.ae2Operations));
            ModularMachinery.log.info(String.format("    Successful Ops:        %10.2f per tick", avg5s.successfulOps));
            ModularMachinery.log.info(String.format("    Items Transferred:     %10.2f per tick", avg5s.itemsTransferred));
            ModularMachinery.log.info(String.format("    Efficiency Ratio:      %10.1f%%", avg5s.getEfficiencyPercent()));

            // 30 second average
            EnhancedTickSample avg30s = samples30s.getAverage();
            ModularMachinery.log.info("  Last 30 seconds average:");
            ModularMachinery.log.info(String.format("    Tick Time:             %10.2f µs", avg30s.tickTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    ProcessWork Time:      %10.2f µs", avg30s.processWorkTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    GetSlots Time:         %10.2f µs", avg30s.getSlotsTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    Slots Checked:         %10.2f per tick", avg30s.slotsChecked));
            ModularMachinery.log.info(String.format("    AE2 Operations:        %10.2f per tick", avg30s.ae2Operations));
            ModularMachinery.log.info(String.format("    Successful Ops:        %10.2f per tick", avg30s.successfulOps));
            ModularMachinery.log.info(String.format("    Items Transferred:     %10.2f per tick", avg30s.itemsTransferred));
            ModularMachinery.log.info(String.format("    Efficiency Ratio:      %10.1f%%", avg30s.getEfficiencyPercent()));

            // 60 second average
            EnhancedTickSample avg60s = samples60s.getAverage();
            ModularMachinery.log.info("  Last 60 seconds average:");
            ModularMachinery.log.info(String.format("    Tick Time:             %10.2f µs", avg60s.tickTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    ProcessWork Time:      %10.2f µs", avg60s.processWorkTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    GetSlots Time:         %10.2f µs", avg60s.getSlotsTimeNanos / 1000.0));
            ModularMachinery.log.info(String.format("    Slots Checked:         %10.2f per tick", avg60s.slotsChecked));
            ModularMachinery.log.info(String.format("    AE2 Operations:        %10.2f per tick", avg60s.ae2Operations));
            ModularMachinery.log.info(String.format("    Successful Ops:        %10.2f per tick", avg60s.successfulOps));
            ModularMachinery.log.info(String.format("    Items Transferred:     %10.2f per tick", avg60s.itemsTransferred));
            ModularMachinery.log.info(String.format("    Efficiency Ratio:      %10.1f%%", avg60s.getEfficiencyPercent()));
        }

        void writeCSV(PrintWriter writer, String timestamp, long reportNumber) {
            long ticks = totalTicks.get();
            if (ticks == 0) {
                return; // Don't write if no data
            }

            EnhancedTickSample avg1s = samples1s.getAverage();
            EnhancedTickSample avg5s = samples5s.getAverage();
            EnhancedTickSample avg30s = samples30s.getAverage();
            EnhancedTickSample avg60s = samples60s.getAverage();

            // Enhanced CSV format with ALL metrics
            writer.printf("%s,%d,%s,%d," +
                            "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f," +
                            "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f," +
                            "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f," +
                            "%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.4f%n",
                    timestamp,
                    reportNumber,
                    busTypeName,
                    ticks,
                    // 1s averages
                    avg1s.tickTimeNanos / 1000.0,
                    avg1s.processWorkTimeNanos / 1000.0,
                    avg1s.getSlotsTimeNanos / 1000.0,
                    avg1s.slotsChecked,
                    avg1s.ae2Operations,
                    avg1s.successfulOps,
                    avg1s.itemsTransferred,
                    avg1s.getEfficiencyRatio(),
                    // 5s averages
                    avg5s.tickTimeNanos / 1000.0,
                    avg5s.processWorkTimeNanos / 1000.0,
                    avg5s.getSlotsTimeNanos / 1000.0,
                    avg5s.slotsChecked,
                    avg5s.ae2Operations,
                    avg5s.successfulOps,
                    avg5s.itemsTransferred,
                    avg5s.getEfficiencyRatio(),
                    // 30s averages
                    avg30s.tickTimeNanos / 1000.0,
                    avg30s.processWorkTimeNanos / 1000.0,
                    avg30s.getSlotsTimeNanos / 1000.0,
                    avg30s.slotsChecked,
                    avg30s.ae2Operations,
                    avg30s.successfulOps,
                    avg30s.itemsTransferred,
                    avg30s.getEfficiencyRatio(),
                    // 60s averages
                    avg60s.tickTimeNanos / 1000.0,
                    avg60s.processWorkTimeNanos / 1000.0,
                    avg60s.getSlotsTimeNanos / 1000.0,
                    avg60s.slotsChecked,
                    avg60s.ae2Operations,
                    avg60s.successfulOps,
                    avg60s.itemsTransferred,
                    avg60s.getEfficiencyRatio()
            );
        }
    }

    /**
     * Enhanced tick sample data with AE2 operation metrics
     */
    private static class EnhancedTickSample {
        final long tickTimeNanos;
        final long processWorkTimeNanos;
        final long getSlotsTimeNanos;
        final double slotsChecked;
        final double ae2Operations;
        final double successfulOps;
        final double itemsTransferred;

        EnhancedTickSample(long tickTimeNanos, long processWorkTimeNanos, long getSlotsTimeNanos,
                           double slotsChecked, double ae2Operations, double successfulOps, double itemsTransferred) {
            this.tickTimeNanos = tickTimeNanos;
            this.processWorkTimeNanos = processWorkTimeNanos;
            this.getSlotsTimeNanos = getSlotsTimeNanos;
            this.slotsChecked = slotsChecked;
            this.ae2Operations = ae2Operations;
            this.successfulOps = successfulOps;
            this.itemsTransferred = itemsTransferred;
        }

        double getEfficiencyRatio() {
            return ae2Operations > 0 ? successfulOps / ae2Operations : 0.0;
        }

        double getEfficiencyPercent() {
            return getEfficiencyRatio() * 100.0;
        }

        static EnhancedTickSample zero() {
            return new EnhancedTickSample(0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Thread-safe ring buffer for maintaining time-window samples with enhanced metrics
     */
    private static class EnhancedRingBuffer {
        private final EnhancedTickSample[] buffer;
        private final int capacity;
        private volatile int writeIndex = 0;
        private volatile int size = 0;

        EnhancedRingBuffer(int capacity) {
            this.capacity = capacity;
            this.buffer = new EnhancedTickSample[capacity];
        }

        synchronized void add(EnhancedTickSample sample) {
            buffer[writeIndex] = sample;
            writeIndex = (writeIndex + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        }

        synchronized void updateLastSampleMetrics(int slotsChecked, int ae2Ops, int successfulOps, long itemsTransferred) {
            // Update the most recently added sample with enhanced metrics
            int lastIndex = (writeIndex - 1 + capacity) % capacity;
            if (size > 0 && buffer[lastIndex] != null) {
                EnhancedTickSample oldSample = buffer[lastIndex];
                buffer[lastIndex] = new EnhancedTickSample(
                        oldSample.tickTimeNanos,
                        oldSample.processWorkTimeNanos,
                        oldSample.getSlotsTimeNanos,
                        slotsChecked,
                        ae2Ops,
                        successfulOps,
                        itemsTransferred
                );
            }
        }

        synchronized EnhancedTickSample getAverage() {
            if (size == 0) {
                return EnhancedTickSample.zero();
            }

            long sumTickTime = 0;
            long sumProcessWorkTime = 0;
            long sumGetSlotsTime = 0;
            double sumSlotsChecked = 0;
            double sumAE2Ops = 0;
            double sumSuccessfulOps = 0;
            double sumItemsTransferred = 0;

            for (int i = 0; i < size; i++) {
                EnhancedTickSample sample = buffer[i];
                if (sample != null) {
                    sumTickTime += sample.tickTimeNanos;
                    sumProcessWorkTime += sample.processWorkTimeNanos;
                    sumGetSlotsTime += sample.getSlotsTimeNanos;
                    sumSlotsChecked += sample.slotsChecked;
                    sumAE2Ops += sample.ae2Operations;
                    sumSuccessfulOps += sample.successfulOps;
                    sumItemsTransferred += sample.itemsTransferred;
                }
            }

            return new EnhancedTickSample(
                    sumTickTime / size,
                    sumProcessWorkTime / size,
                    sumGetSlotsTime / size,
                    sumSlotsChecked / size,
                    sumAE2Ops / size,
                    sumSuccessfulOps / size,
                    sumItemsTransferred / size
            );
        }
    }
}