package net.kyver.placy;

import net.kyver.placy.config.EnvironmentSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootApplication
public class Application {
    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    @Autowired
    private Environment environment;

    @Autowired
    private EnvironmentSetup environmentSetup;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String port = System.getenv("SERVER_PORT");
        if (port == null || port.isEmpty()) {
            port = environment.getProperty("server.port", "8080");
        }
        String contextPath = environment.getProperty("server.servlet.context-path", "/");

        logger.info("=================================================================");
        logger.info("Placy app is now running");
        logger.info("Server is running on: http://localhost:{}{}", port, contextPath);
        logger.info("API endpoint: http://localhost:{}/api/v1/transform", port);

        if (environmentSetup.isSecretKeyEnabled()) {
            logger.info("🔒 SECRET_KEY validation is ENABLED - API requests require X-Secret-Key header");
        } else {
            logger.info("🔓 SECRET_KEY validation is DISABLED - API requests do not require authentication");
        }

        if (environmentSetup.isAsyncProcessingEnabled()) {
            logger.info("⚡ ASYNC_PROCESSING is ENABLED - files will be processed concurrently for better performance");
        } else {
            logger.info("🐌 ASYNC_PROCESSING is DISABLED - files will be processed synchronously (set ASYNC_PROCESSING=true for better performance)");
        }

        logger.info("=================================================================");
    }
}
