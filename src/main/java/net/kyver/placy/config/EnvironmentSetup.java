package net.kyver.placy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnvironmentSetup {

    @Value("${SECRET_KEY:#{null}}")
    private String secretKey;

    public String getSecretKey() {
        return secretKey;
    }

    public boolean isSecretKeyEnabled() {
        return secretKey != null && !secretKey.trim().isEmpty();
    }
}
