package net.kyver.placy.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.Map;
import java.util.jar.JarFile;

@Component
public class Updater {
    
    private static final String ORIGINAL_JAR_NAME = "Placy.jar";
    private static final String NEW_JAR_NAME = "Placy.jar.new";
    private static final String BACKUP_JAR_NAME = "Placy.jar.backup";
    private static final String TEMP_JAR_NAME = "Placy.jar.temp";

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_BOLD = "\u001B[1m";
    
    private static final String LOG_PREFIX = ANSI_BOLD + ANSI_CYAN + "[ProcessUpdater]" + ANSI_RESET;
    
    public void checkAndHandleUpdate() {
        try {
            String currentJarPath = getCurrentJarPath();
            File currentJarFile = new File(currentJarPath);
            String jarName = currentJarFile.getName();

            log("INFO", "Starting update check for: " + jarName);

            if (jarName.equals(NEW_JAR_NAME)) {
                log("INFO", "Detected " + NEW_JAR_NAME + " - completing update process...");
                completeUpdateProcess(currentJarFile);
                return;
            }

            if (jarName.equals(ORIGINAL_JAR_NAME)) {
                checkForUpdates(currentJarFile);
            }

        } catch (Exception e) {
            log("ERROR", "Update process failed: " + e.getMessage());
            // Improved logging: removed printStackTrace and replaced with log
        }
    }
    
    private void checkForUpdates(File currentJarFile) throws Exception {
        log("INFO", "Checking for updates...");
        
        String currentVersion = getCurrentVersion(currentJarFile);
        String latestVersion = getLatestVersion();
        
        log("INFO", "Current version: " + (currentVersion != null ? currentVersion : "Unknown"));
        log("INFO", "Latest version: " + latestVersion);
        
        if (currentVersion == null || !normalizeVersion(currentVersion).equals(normalizeVersion(latestVersion))) {
            log("INFO", ANSI_YELLOW + "Update available! Starting download..." + ANSI_RESET);
            startUpdateProcess(currentJarFile);
        } else {
            log("SUCCESS", "Application is up to date!");
        }
    }
    
    private void startUpdateProcess(File currentJarFile) throws Exception {
        File newJarFile = new File(currentJarFile.getParent(), NEW_JAR_NAME);
        File tempJarFile = new File(currentJarFile.getParent(), TEMP_JAR_NAME);

        try {
            downloadLatestVersion(tempJarFile);

            if (newJarFile.exists()) {
                Files.delete(newJarFile.toPath());
            }
            Files.move(tempJarFile.toPath(), newJarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            
            log("SUCCESS", "Download completed. Starting new process...");

            startNewProcess(newJarFile);

            log("INFO", "Shutting down current process...");
            System.exit(0);
            
        } catch (Exception e) {
            cleanupFile(tempJarFile);
            cleanupFile(newJarFile);
            throw e;
        }
    }
    
    private void completeUpdateProcess(File newJarFile) throws Exception {
        File targetJarFile = new File(newJarFile.getParent(), ORIGINAL_JAR_NAME);
        File backupJarFile = new File(newJarFile.getParent(), BACKUP_JAR_NAME);

        try {
            log("INFO", "Completing update process...");

            Thread.sleep(2000);

            if (targetJarFile.exists()) {
                log("INFO", "Creating backup of existing " + ORIGINAL_JAR_NAME + "...");
                if (backupJarFile.exists()) {
                    Files.delete(backupJarFile.toPath());
                }
                Files.copy(targetJarFile.toPath(), backupJarFile.toPath());
                Files.delete(targetJarFile.toPath());
            }

            log("INFO", "Installing new version...");
            Files.copy(newJarFile.toPath(), targetJarFile.toPath());

            if (!targetJarFile.exists() || targetJarFile.length() == 0) {
                throw new RuntimeException("New jar file verification failed");
            }
            
            log("SUCCESS", "Update completed successfully!");

            startNewProcess(targetJarFile);

            cleanupUpdateFiles(newJarFile, backupJarFile);

            log("INFO", "Shutting down update process...");
            System.exit(0);
            
        } catch (Exception e) {
            log("ERROR", "Update completion failed: " + e.getMessage());

            if (backupJarFile.exists() && !targetJarFile.exists()) {
                log("WARN", "Restoring from backup...");
                Files.move(backupJarFile.toPath(), targetJarFile.toPath());
                startNewProcess(targetJarFile);
            }
            
            throw e;
        }
    }
    
    private void startNewProcess(File jarFile) throws Exception {
        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        
        ProcessBuilder processBuilder = new ProcessBuilder(
            javaPath,
            "-jar",
            jarFile.getAbsolutePath()
        );
        
        processBuilder.directory(jarFile.getParentFile());

        Map<String, String> env = processBuilder.environment();
        env.putAll(System.getenv());
        
        log("INFO", "Starting new process: " + jarFile.getName());
        
        Process process = processBuilder.start();

        Thread.sleep(1000);
        
        if (!process.isAlive()) {
            throw new RuntimeException("New process failed to start");
        }
        
        log("SUCCESS", "New process started successfully!");
    }
    
    private String getCurrentVersion(File jarFile) {
        if (!jarFile.exists()) {
            return null;
        }
        
        try (JarFile jar = new JarFile(jarFile)) {
            String[] possiblePaths = {
                "BOOT-INF/classes/application.yml",
                "BOOT-INF/classes/application.yaml", 
                "application.yml",
                "application.yaml",
                "BOOT-INF/classes/version.yml",
                "version.yml"
            };
            
            for (String path : possiblePaths) {
                var entry = jar.getJarEntry(path);
                if (entry != null) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        Yaml yaml = new Yaml();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = yaml.load(input);

                        String[] versionKeys = {"version", "app.version", "application.version"};
                        for (String key : versionKeys) {
                            Object version = getNestedValue(data, key);
                            if (version != null) {
                                return version.toString();
                            }
                        }
                    }
                }
            }
            
            log("WARN", "Version information not found in jar");
            return null;
            
        } catch (Exception e) {
            log("ERROR", "Error reading version from jar: " + e.getMessage());
            return null;
        }
    }
    
    private Object getNestedValue(Map<String, Object> map, String key) {
        if (key.contains(".")) {
            String[] parts = key.split("\\.", 2);
            Object value = map.get(parts[0]);
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                return getNestedValue(nestedMap, parts[1]);
            }
            return null;
        }
        return map.get(key);
    }
    
    private String getLatestVersion() throws Exception {
        URL url = new URL("https://api.github.com/repos/Harfull/Placy/releases/latest");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "Placy-Updater/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("Failed to get latest version: " + responseCode);
            }

            try (InputStream inputStream = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                return jsonObject.get("tag_name").getAsString();
            }
        } finally {
            connection.disconnect();
        }
    }
    
    private void downloadLatestVersion(File targetFile) throws Exception {
        log("INFO", "Downloading latest version...");

        URL url = new URL("https://github.com/Harfull/Placy/releases/latest/download/" + ORIGINAL_JAR_NAME);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "Placy-Updater/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("Failed to download: " + responseCode);
            }

            long totalBytes = connection.getContentLengthLong();

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(targetFile)) {

                byte[] buffer = new byte[8192];
                long downloadedBytes = 0;
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;

                    if (totalBytes > 0 && downloadedBytes % (1024 * 100) == 0) {
                        long progress = (downloadedBytes * 100) / totalBytes;
                        log("INFO", "Download progress: " + progress + "%");
                    }
                }
            }
        } finally {
            connection.disconnect();
        }

        if (!targetFile.exists() || targetFile.length() == 0) {
            throw new RuntimeException("Downloaded file is invalid or empty");
        }

        log("SUCCESS", "Download completed: " + targetFile.getName());
    }
    
    private void cleanupUpdateFiles(File newJarFile, File backupJarFile) {
        try {
            String currentJarPath = getCurrentJarPath();
            File currentJarFile = new File(currentJarPath);
            
            File parentDir = currentJarFile.getParentFile();
            File[] newJarFiles = parentDir.listFiles((dir, name) -> 
                name.equals(NEW_JAR_NAME));
            
            if (newJarFiles != null) {
                for (File file : newJarFiles) {
                    if (!file.getAbsolutePath().equals(currentJarFile.getAbsolutePath())) {
                        log("INFO", "Cleaning up old " + NEW_JAR_NAME + ": " + file.getName());
                        Files.deleteIfExists(file.toPath());
                    }
                }
            }

            if (backupJarFile.exists()) {
                log("INFO", "Cleaning up backup file...");
                Files.deleteIfExists(backupJarFile.toPath());
            }
            
        } catch (Exception e) {
            log("WARN", "Cleanup failed: " + e.getMessage());
        }
    }
    
    private void cleanupFile(File file) {
        try {
            if (file.exists()) {
                Files.delete(file.toPath());
            }
        } catch (Exception e) {
            log("WARN", "Failed to cleanup file: " + file.getName());
        }
    }
    
    private String getCurrentJarPath() {
        try {
            return new File(Updater.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getPath();
        } catch (Exception e) {
            String jarPath = ManagementFactory.getRuntimeMXBean().getClassPath();
            if (jarPath.contains(".jar")) {
                return jarPath.split(System.getProperty("path.separator"))[0];
            }
            throw new RuntimeException("Could not determine current JAR path");
        }
    }
    
    private String normalizeVersion(String version) {
        if (version == null) return "";
        return version.trim().replaceAll("^[vV]", "");
    }
    
    private void log(String level, String message) {
        String color = switch (level) {
            case "ERROR" -> ANSI_RED;
            case "WARN" -> ANSI_YELLOW;
            case "SUCCESS" -> ANSI_GREEN;
            default -> "";
        };
        
        System.out.println(LOG_PREFIX + " " + color + level + ANSI_RESET + " " + message);
    }
}