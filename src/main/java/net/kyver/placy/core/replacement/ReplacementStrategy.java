package net.kyver.placy.core.replacement;

import net.kyver.placy.core.ProcessingResult;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Map;

public interface ReplacementStrategy {

    ProcessingResult replace(InputStream input,
                           OutputStream output,
                           Map<String, String> placeholders,
                           Charset charset);

    byte[] replace(byte[] content,
                  Map<String, String> placeholders,
                  Charset charset);

    String getStrategyName();

    boolean supportsStreaming();

    boolean supportsParallelProcessing();

    long getRecommendedMinimumSize();
}
