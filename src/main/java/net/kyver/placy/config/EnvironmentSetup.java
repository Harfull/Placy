package net.kyver.placy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class EnvironmentSetup {
    private static volatile boolean initialized = false;
    private static volatile boolean asyncProcessingEnabled = true;
    private static volatile boolean performanceMonitoringEnabled = true;
    private static volatile boolean recursiveArchivesEnabled = false;
    private static volatile boolean checkUpdatesEnabled = true;
    private static volatile int maxFileSize = 1024 * 1024 * 1024;
    private static volatile int maxConcurrentFiles = 10;

    public static synchronized void loadDotEnv() {
        if (initialized) {
            return;
        }

        Path path = Paths.get(".env");
        if (Files.exists(path)) {
            loadFromEnvFile(path);
        }

        loadSystemConfigurations();

        validateSSLConfiguration();

        configurePerformanceSettings();

        initialized = true;
    }

    private static void loadFromEnvFile(Path path) {
        try {
            List<String> lines = Files.readAllLines(path);

            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int eq = line.indexOf('=');
                if (eq <= 0) continue;

                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }

                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }

        } catch (IOException ignored) {
        }
    }

    private static void loadSystemConfigurations() {
        String serverPort = getConfigValue("SERVER_PORT", "8080");
        System.setProperty("server.port", serverPort);

        String httpsEnabled = getConfigValue("HTTPS_ENABLED", "false");
        System.setProperty("server.ssl.enabled", httpsEnabled);

        String maxFileSize = getConfigValue("MAX_FILE_SIZE", "1GB");
        String maxRequestSize = getConfigValue("MAX_REQUEST_SIZE", "1GB");
        System.setProperty("spring.servlet.multipart.max-file-size", maxFileSize);
        System.setProperty("spring.servlet.multipart.max-request-size", maxRequestSize);

        String secretKey = getConfigValue("SECRET_KEY", "");
        if (!secretKey.isEmpty() && !secretKey.equals("your_secret_key_here")) {
            System.setProperty("SECRET_KEY", secretKey);
        }

        String asyncProcessing = getConfigValue("ASYNC_PROCESSING", "true");
        asyncProcessingEnabled = "true".equalsIgnoreCase(asyncProcessing) || "1".equals(asyncProcessing);

        String perfMonitoring = getConfigValue("PERFORMANCE_MONITORING_ENABLED", "true");
        performanceMonitoringEnabled = "true".equalsIgnoreCase(perfMonitoring) || "1".equals(perfMonitoring);

        String recursiveArchives = getConfigValue("RECURSIVE_ARCHIVES", "false");
        recursiveArchivesEnabled = "true".equalsIgnoreCase(recursiveArchives) || "1".equals(recursiveArchives);

        String checkUpdates = getConfigValue("CHECK_UPDATES", "true");
        checkUpdatesEnabled = "true".equalsIgnoreCase(checkUpdates) || "1".equals(checkUpdates);

        try {
            maxFileSize = parseFileSize(getConfigValue("MAX_FILE_SIZE", "1GB"));
            EnvironmentSetup.maxFileSize = Integer.parseInt(maxFileSize);
        } catch (NumberFormatException e) {
            EnvironmentSetup.maxFileSize = 1024 * 1024 * 1024;
        }

        String maxConcurrentStr = getConfigValue("MAX_CONCURRENT_FILES", "10");
        try {
            maxConcurrentFiles = Integer.parseInt(maxConcurrentStr);
        } catch (NumberFormatException e) {
            maxConcurrentFiles = 10;
        }

        configureJVMSettings();
    }

    private static String parseFileSize(String sizeStr) {
        if (sizeStr == null || sizeStr.isEmpty()) {
            return "1073741824";
        }

        sizeStr = sizeStr.trim().toUpperCase();

        long multiplier = 1;
        if (sizeStr.endsWith("KB")) {
            multiplier = 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("MB")) {
            multiplier = 1024 * 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("GB")) {
            multiplier = 1024 * 1024 * 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        }

        try {
            long size = Long.parseLong(sizeStr.trim()) * multiplier;
            return String.valueOf(size);
        } catch (NumberFormatException e) {
            return "1073741824";
        }
    }

    private static void validateSSLConfiguration() {
        String https = getConfigValue("HTTPS_ENABLED", "false");
        if ("true".equalsIgnoreCase(https.trim()) || "1".equals(https.trim())) {
            String keystorePath = getConfigValue("SSL_KEYSTORE_PATH", null);
            if (keystorePath == null || keystorePath.trim().isEmpty()) {
                System.setProperty("HTTPS_ENABLED", "false");
            } else if (!Files.exists(Paths.get(keystorePath))) {
                System.setProperty("HTTPS_ENABLED", "false");
            }
        }
    }

    private static void configurePerformanceSettings() {
        String gcType = getConfigValue("JVM_GC_TYPE", "G1GC");
        String heapSize = getConfigValue("JVM_HEAP_SIZE", "auto");

        long maxMemory = Runtime.getRuntime().maxMemory();
        int optimalBufferSize = calculateOptimalBufferSize(maxMemory);
        System.setProperty("placy.buffer.size", String.valueOf(optimalBufferSize));

        int cores = Runtime.getRuntime().availableProcessors();
        System.setProperty("placy.threads.io", String.valueOf(Math.max(8, cores * 2)));
        System.setProperty("placy.threads.cpu", String.valueOf(cores));
    }

    private static void configureJVMSettings() {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("java.net.preferIPv4Stack", "true");

        System.setProperty("networkaddress.cache.ttl", "60");
        System.setProperty("networkaddress.cache.negative.ttl", "10");
    }

    private static int calculateOptimalBufferSize(long maxMemory) {
        long bufferSize = maxMemory / 1000;

        bufferSize = Math.max(64 * 1024, Math.min(bufferSize, 1024 * 1024));

        return (int) bufferSize;
    }

    private static String getConfigValue(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null) {
            value = System.getProperty(key);
        }
        return value != null ? value : defaultValue;
    }

    public static boolean isAsyncProcessingEnabled() {
        return asyncProcessingEnabled;
    }

    public static boolean isPerformanceMonitoringEnabled() {
        return performanceMonitoringEnabled;
    }

    public static boolean isRecursiveArchivesEnabled() {
        return recursiveArchivesEnabled;
    }

    public static boolean isCheckUpdatesEnabled() {
        return checkUpdatesEnabled;
    }

    public static int getMaxFileSize() {
        return maxFileSize;
    }

    public static int getMaxConcurrentFiles() {
        return maxConcurrentFiles;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static synchronized void reinitialize() {
        initialized = false;
        loadDotEnv();
    }
}
