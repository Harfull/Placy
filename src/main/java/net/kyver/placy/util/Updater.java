package net.kyver.placy.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyver.placy.Application;
import net.kyver.placy.config.EnvironmentSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.Map;

@Component
public class Updater {
    
    private static final Logger logger = LoggerFactory.getLogger(Updater.class);

    private static final String ORIGINAL_JAR_NAME = "Placy.jar";
    private static final String NEW_JAR_NAME = "Placy.jar.new";
    private static final String BACKUP_JAR_NAME = "Placy.jar.backup";
    private static final String TEMP_JAR_NAME = "Placy.jar.temp";

    public void checkAndHandleUpdate() {
        try {
            EnvironmentSetup.loadDotEnv();

            if (!EnvironmentSetup.isCheckUpdatesEnabled()) {
                logger.info("Update checking is disabled via CHECK_UPDATES environment variable");
                return;
            }

            String currentJarPath = getCurrentJarPath();
            File currentJarFile = new File(currentJarPath);
            String jarName = currentJarFile.getName();

            logger.debug("Starting update check for: {}", jarName);

            if (jarName.equals(NEW_JAR_NAME)) {
                logger.debug("Detected {} - completing update process...", NEW_JAR_NAME);
                completeUpdateProcess(currentJarFile);
                return;
            }

            if (jarName.equals(ORIGINAL_JAR_NAME)) {
                checkForUpdates(currentJarFile);
            }

        } catch (Exception e) {
            logger.error("Update process failed: {}", e.getMessage(), e);
        }
    }
    
    private void checkForUpdates(File currentJarFile) throws Exception {
        logger.info("🔍 Checking for updates...");

        String currentVersion = getCurrentVersion();
        String latestVersion = getLatestVersion();
        
        logger.info("Current version: {}", currentVersion);
        logger.debug("Latest version: {}", latestVersion);

        if (!normalizeVersion(currentVersion).equals(normalizeVersion(latestVersion))) {
            logger.info("🆕 Update available! New version: {} - Starting download...", latestVersion);
            startUpdateProcess(currentJarFile);
        } else {
            logger.info("✅ Application is up to date!");
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
            
            logger.info("✅ Download completed. Starting new process...");

            startNewProcess(newJarFile);

            logger.info("Shutting down current process...");
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
            logger.info("Completing update process...");

            Thread.sleep(2000);

            if (targetJarFile.exists()) {
                logger.debug("Creating backup of existing {}...", ORIGINAL_JAR_NAME);
                if (backupJarFile.exists()) {
                    Files.delete(backupJarFile.toPath());
                }
                Files.copy(targetJarFile.toPath(), backupJarFile.toPath());
                Files.delete(targetJarFile.toPath());
            }

            logger.info("Installing new version...");
            Files.copy(newJarFile.toPath(), targetJarFile.toPath());

            if (!targetJarFile.exists() || targetJarFile.length() == 0) {
                throw new RuntimeException("New jar file verification failed");
            }
            
            logger.info("✅ Update completed successfully!");

            startNewProcess(targetJarFile);

            cleanupUpdateFiles(newJarFile, backupJarFile);

            logger.info("Shutting down update process...");
            System.exit(0);
            
        } catch (Exception e) {
            logger.error("Update completion failed: {}", e.getMessage());

            if (backupJarFile.exists() && !targetJarFile.exists()) {
                logger.warn("Restoring from backup...");
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
        
        logger.info("Starting new process: {}", jarFile.getName());

        Process process = processBuilder.start();

        Thread.sleep(1000);
        
        if (!process.isAlive()) {
            throw new RuntimeException("New process failed to start");
        }
        
        logger.info("✅ New process started successfully!");
    }
    
    private String getCurrentVersion() {
        return Application.getVersion();
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
        logger.info("Downloading latest version...");

        URL url = new URL("https://github.com/Harfull/Placy/releases/latest/download/" + ORIGINAL_JAR_NAME);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "Placy-Updater/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException("Failed to download: HTTP " + responseCode);
            }

            long totalBytes = connection.getContentLengthLong();

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(targetFile)) {

                byte[] buffer = new byte[8192];
                long downloadedBytes = 0;
                int bytesRead;
                int lastLoggedProgress = 0;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;

                    if (totalBytes > 0) {
                        int progress = (int) ((downloadedBytes * 100) / totalBytes);
                        if (progress >= lastLoggedProgress + 25) {
                            logger.info("Download progress: {}%", progress);
                            lastLoggedProgress = progress;
                        }
                    }
                }
            }
        } finally {
            connection.disconnect();
        }

        if (!targetFile.exists() || targetFile.length() == 0) {
            throw new RuntimeException("Downloaded file is invalid or empty");
        }

        logger.info("✅ Download completed: {}", targetFile.getName());
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
                        logger.debug("Cleaning up old {}: {}", NEW_JAR_NAME, file.getName());
                        Files.deleteIfExists(file.toPath());
                    }
                }
            }

            if (backupJarFile.exists()) {
                logger.debug("Cleaning up backup file...");
                Files.deleteIfExists(backupJarFile.toPath());
            }
            
        } catch (Exception e) {
            logger.warn("Cleanup failed: {}", e.getMessage());
        }
    }
    
    private void cleanupFile(File file) {
        try {
            if (file.exists()) {
                Files.delete(file.toPath());
            }
        } catch (Exception e) {
            logger.warn("Failed to cleanup file: {}", file.getName());
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
}