package net.kyver.placy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class EnvironmentSetup {

    public static void loadDotEnv() {
        Path path = Paths.get(".env");
        if (!Files.exists(path)) {
            return;
        }
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

            String https = System.getenv("HTTPS_ENABLED");
            if (https == null) {
                https = System.getProperty("HTTPS_ENABLED");
            }
            if (https != null && ("true".equalsIgnoreCase(https.trim()) || "1".equals(https.trim()))) {
                String ks = System.getenv("SSL_KEYSTORE_PATH");
                if (ks == null) {
                    ks = System.getProperty("SSL_KEYSTORE_PATH");
                }
                if (ks == null || ks.trim().isEmpty()) {
                    System.err.println("WARNING: HTTPS_ENABLED=true but no SSL_KEYSTORE_PATH configured. Disabling HTTPS to prevent startup failure.");
                    System.setProperty("HTTPS_ENABLED", "false");
                }
            }

        } catch (IOException e) {
            System.err.println("Could not read .env file: " + e.getMessage());
        }
    }

    public static String getSecretKey() {
        String key = System.getenv("SECRET_KEY");
        if (key == null) {
            key = System.getProperty("SECRET_KEY");
        }
        return key;
    }

    public static boolean isSecretKeyEnabled() {
        String key = getSecretKey();
        return key != null && !key.trim().isEmpty();
    }

    public static boolean isAsyncProcessingEnabled() {
        String async = System.getenv("ASYNC_PROCESSING");
        if (async == null) {
            async = System.getProperty("ASYNC_PROCESSING");
        }
        return async != null && ("true".equalsIgnoreCase(async.trim()) || "1".equals(async.trim()));
    }

    public static boolean isDebugModeEnabled() {
        String debug = System.getenv("DEBUG_MODE");
        if (debug == null) {
            debug = System.getProperty("DEBUG_MODE");
        }
        return debug != null && ("true".equalsIgnoreCase(debug.trim()) || "1".equals(debug.trim()));
    }
}
