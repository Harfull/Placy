package net.kyver.placy;

import net.kyver.placy.config.EnvironmentSetup;
import net.kyver.placy.util.Updater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class Application {
    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    private static final String VERSION = "v1.4.1";

    @Autowired
    private Updater updater;

    public static String getVersion() {
        return VERSION;
    }

    public static void main(String[] args) {
        EnvironmentSetup.loadDotEnv();

        printStartupBanner();

        try {
            SpringApplication app = new SpringApplication(Application.class);

            configureApplication(app);

            app.run(args);

        } catch (Exception e) {
            logger.error("Failed to start Placy application: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("✅ Started Placy application successfully");
        logger.info("🚀 Application is running on http://localhost:{}", System.getProperty("server.port", "8080"));

        if (EnvironmentSetup.isAsyncProcessingEnabled()) {
            logger.info("⚡ Asynchronous processing is enabled");
        } else {
            logger.info("⚡ Asynchronous processing is disabled");
        }

        if (EnvironmentSetup.isCheckUpdatesEnabled()) {
            try {
                updater.checkAndHandleUpdate();
            } catch (Exception e) {
                logger.warn("Update check failed: {}", e.getMessage());
            }
        } else {
            logger.info("Update checking is disabled via CHECK_UPDATES environment variable");
        }
    }

    private static void configureApplication(SpringApplication app) {
        System.setProperty("spring.main.lazy-initialization", "false");
        System.setProperty("spring.jpa.open-in-view", "false");
        System.setProperty("spring.output.ansi.enabled", "detect");

        System.setProperty("server.compression.enabled", "true");
        System.setProperty("server.compression.mime-types",
                          "text/html,text/xml,text/plain,text/css,text/javascript,application/javascript,application/json");
        System.setProperty("server.http2.enabled", "true");

        System.setProperty("management.endpoints.web.exposure.include", "health,info,metrics,prometheus");
        System.setProperty("management.endpoint.health.show-details", "when-authorized");
    }
    private static void printStartupBanner() {
        String BLUE = "\u001B[34m";
        String RESET = "\u001B[0m";
        String BOLD = "\u001B[1m";

        System.out.println();
        System.out.println(BLUE + "╔═══════════════════════════════════════╗" + RESET);
        System.out.println(BLUE + "║" + RESET + "                                       " + BLUE + "║" + RESET);
        System.out.println(BLUE + "║" + RESET + "  " + BOLD + "PLACY - Processing System" + RESET + "            " + BLUE + "║" + RESET);
        System.out.println(BLUE + "║" + RESET + "  Version: " + VERSION + "                         " + BLUE + "║" + RESET);
        System.out.println(BLUE + "║" + RESET + "                                       " + BLUE + "║" + RESET);
        System.out.println(BLUE + "╚═══════════════════════════════════════╝" + RESET);
        System.out.println();
    }
}
