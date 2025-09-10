package net.kyver.placy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    @Autowired
    private EnvironmentSetup environmentSetup;

    @Bean(name = "fileProcessingExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int corePoolSize = Math.max(2, availableProcessors / 2);
        int maxPoolSize = Math.max(4, availableProcessors * 2);

        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("AsyncFileProcessor-");
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);

        executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                logger.warn("Async file processing task rejected - queue full. Current queue size: {}, active threads: {}",
                           executor.getQueue().size(), executor.getActiveCount());
                try {
                    r.run();
                } catch (Exception e) {
                    logger.error("Failed to execute rejected async task synchronously", e);
                }
            }
        });

        executor.initialize();

        if (environmentSetup.isAsyncProcessingEnabled()) {
            logger.info("Async file processing ENABLED - Core pool size: {}, Max pool size: {}, Queue capacity: {}",
                       corePoolSize, maxPoolSize, 100);
        } else {
            logger.info("Async file processing DISABLED - using synchronous processing");
        }

        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, objects) -> {
            logger.error("Async file processing error in method: {} with parameters: {}",
                        method.getName(), objects, throwable);
        };
    }
}
