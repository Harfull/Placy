package net.kyver.placy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnvironmentSetup {

    @Value("${SECRET_KEY:#{null}}")
    private String secretKey;

    @Value("${ASYNC_PROCESSING:#{null}}")
    private String asyncProcessing;

    public String getSecretKey() {
        return secretKey;
    }

    public boolean isSecretKeyEnabled() {
        return secretKey != null && !secretKey.trim().isEmpty();
    }

    public boolean isAsyncProcessingEnabled() {
        return asyncProcessing != null &&
               ("true".equalsIgnoreCase(asyncProcessing.trim()) || "1".equals(asyncProcessing.trim()));
    }

    public String getAsyncProcessing() {
        return asyncProcessing;
    }
}
