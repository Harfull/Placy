package net.kyver.placy.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class PerformanceProfiler {
    private static final Logger logger = LoggerFactory.getLogger(PerformanceProfiler.class);

    private final LongAdder totalOperations = new LongAdder();
    private final LongAdder totalProcessingTime = new LongAdder();
    private final LongAdder totalBytesProcessed = new LongAdder();
    private final LongAdder totalReplacements = new LongAdder();

    private final AtomicLong minProcessingTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxProcessingTime = new AtomicLong(0);
    private final AtomicLong maxThroughput = new AtomicLong(0);

    private final ConcurrentHashMap<String, LongAdder> operationCounts = new ConcurrentHashMap<>();

    public void recordProcessing(long processingTimeNanos, long bytesProcessed, int placeholderCount) {
        totalOperations.increment();
        totalProcessingTime.add(processingTimeNanos);
        totalBytesProcessed.add(bytesProcessed);
        totalReplacements.add(placeholderCount);

        minProcessingTime.updateAndGet(current -> Math.min(current, processingTimeNanos));
        maxProcessingTime.updateAndGet(current -> Math.max(current, processingTimeNanos));

        if (processingTimeNanos > 0) {
            long throughput = (bytesProcessed * 1_000_000_000L) / processingTimeNanos;
            maxThroughput.updateAndGet(current -> Math.max(current, throughput));
        }
    }

    public void recordOperation(String operationType) {
        operationCounts.computeIfAbsent(operationType, k -> new LongAdder()).increment();
    }

    public long getAverageProcessingTimeNanos() {
        long ops = totalOperations.sum();
        return ops > 0 ? totalProcessingTime.sum() / ops : 0;
    }

    public long getAverageThroughputBytesPerSecond() {
        long totalTime = totalProcessingTime.sum();
        return totalTime > 0 ? (totalBytesProcessed.sum() * 1_000_000_000L) / totalTime : 0;
    }

    public long getTotalOperations() {
        return totalOperations.sum();
    }

    public long getTotalBytesProcessed() {
        return totalBytesProcessed.sum();
    }

    public long getTotalReplacements() {
        return totalReplacements.sum();
    }

    public long getMinProcessingTimeNanos() {
        long min = minProcessingTime.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }

    public long getMaxProcessingTimeNanos() {
        return maxProcessingTime.get();
    }

    public long getMaxThroughputBytesPerSecond() {
        return maxThroughput.get();
    }

    public long getOperationCount(String operationType) {
        LongAdder counter = operationCounts.get(operationType);
        return counter != null ? counter.sum() : 0;
    }

    public void reset() {
        totalOperations.reset();
        totalProcessingTime.reset();
        totalBytesProcessed.reset();
        totalReplacements.reset();

        minProcessingTime.set(Long.MAX_VALUE);
        maxProcessingTime.set(0);
        maxThroughput.set(0);

        operationCounts.clear();

        logger.debug("Performance profiler metrics reset");
    }

    public String generateReport() {
        long ops = getTotalOperations();
        if (ops == 0) {
            return "No operations recorded";
        }

        StringBuilder report = new StringBuilder();
        report.append("Performance Report:\n");
        report.append(String.format("  Total Operations: %,d\n", ops));
        report.append(String.format("  Total Bytes: %,d (%.2f MB)\n",
                     getTotalBytesProcessed(), getTotalBytesProcessed() / (1024.0 * 1024.0)));
        report.append(String.format("  Total Replacements: %,d\n", getTotalReplacements()));
        report.append(String.format("  Average Time: %.2f ms\n", getAverageProcessingTimeNanos() / 1_000_000.0));
        report.append(String.format("  Min Time: %.2f ms\n", getMinProcessingTimeNanos() / 1_000_000.0));
        report.append(String.format("  Max Time: %.2f ms\n", getMaxProcessingTimeNanos() / 1_000_000.0));
        report.append(String.format("  Average Throughput: %.2f MB/s\n",
                     getAverageThroughputBytesPerSecond() / (1024.0 * 1024.0)));
        report.append(String.format("  Max Throughput: %.2f MB/s\n",
                     getMaxThroughputBytesPerSecond() / (1024.0 * 1024.0)));

        return report.toString();
    }
}
