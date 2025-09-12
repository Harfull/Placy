package net.kyver.placy.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProcessingResult {

    private final long bytesProcessed;
    private final long replacementCount;
    private final long placeholderCount;
    private long processingTimeNanos;
    private final List<String> warnings;
    private final List<String> errors;

    public ProcessingResult(long bytesProcessed, long replacementCount, long placeholderCount) {
        this.bytesProcessed = bytesProcessed;
        this.replacementCount = replacementCount;
        this.placeholderCount = placeholderCount;
        this.warnings = new ArrayList<>();
        this.errors = new ArrayList<>();
    }

    public long getBytesProcessed() {
        return bytesProcessed;
    }

    public long getReplacementCount() {
        return replacementCount;
    }

    public long getPlaceholderCount() {
        return placeholderCount;
    }

    public long getProcessingTimeNanos() {
        return processingTimeNanos;
    }

    public void setProcessingTimeNanos(long processingTimeNanos) {
        this.processingTimeNanos = processingTimeNanos;
    }

    public double getProcessingTimeMillis() {
        return processingTimeNanos / 1_000_000.0;
    }

    public double getThroughputMBps() {
        if (processingTimeNanos == 0) return 0.0;
        double seconds = processingTimeNanos / 1_000_000_000.0;
        double megabytes = bytesProcessed / (1024.0 * 1024.0);
        return megabytes / seconds;
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public void addError(String error) {
        errors.add(error);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public boolean isSuccessful() {
        return !hasErrors();
    }

    @Override
    public String toString() {
        return String.format("ProcessingResult{bytes=%d, replacements=%d, placeholders=%d, time=%.2fms, throughput=%.2fMB/s, warnings=%d, errors=%d}",
                bytesProcessed, replacementCount, placeholderCount, getProcessingTimeMillis(),
                getThroughputMBps(), warnings.size(), errors.size());
    }
}
