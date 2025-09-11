package net.kyver.placy;

import net.kyver.placy.config.EnvironmentSetup;
import net.kyver.placy.util.Logger;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.logging.LogManager;

@SpringBootApplication
@EnableAsync
public class Application {
    public static void main(String[] args) {
        System.setProperty("spring.main.banner-mode", "off");
        System.setProperty("logging.level.org.springframework.boot", "OFF");

        EnvironmentSetup.loadDotEnv();

        Logger.info("Starting Placy Transform Service...");

        try {
            SpringApplication app = new SpringApplication(Application.class);
            LogManager.getLogManager().reset();
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();

            app.setBannerMode(Banner.Mode.OFF);
            var context = app.run(args);

            int port = -1;
            if (context instanceof WebServerApplicationContext ws) {
                var server = ws.getWebServer();
                if (server != null) {
                    port = server.getPort();
                }
            } else {
                String prop = context.getEnvironment().getProperty("local.server.port");
                if (prop != null) {
                    try { port = Integer.parseInt(prop); } catch (NumberFormatException ignored) { }
                }
            }

            Logger.success("Placy started successfully");
            Logger.info("Running on: http://localhost:" + port + "/");
        } catch (Exception e) {
            Logger.error("Failed to start Placy Transform Service: ", e);
            System.exit(1);
        }
    }
}
