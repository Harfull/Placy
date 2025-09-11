package net.kyver.placy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
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

    @Bean(name = "fileProcessingExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int corePoolSize = Math.max(4, availableProcessors);
        int maxPoolSize = Math.max(8, availableProcessors * 4);

        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("AsyncFileProcessor-");
        executor.setKeepAliveSeconds(30);
        executor.setAllowCoreThreadTimeOut(true);

        executor.setRejectedExecutionHandler(new RejectedExecutionHandler() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                logger.warn("File processing task rejected. Queue full. Active: {}, Pool size: {}, Queue size: {}",
                           executor.getActiveCount(), executor.getPoolSize(), executor.getQueue().size());
                if (!executor.isShutdown()) {
                    try {
                        r.run();
                        logger.debug("Executed rejected task synchronously as fallback");
                    } catch (Exception e) {
                        logger.error("Failed to execute rejected task synchronously", e);
                    }
                }
            }
        });

        executor.initialize();

        logger.info("Initialized async file processing executor - Core: {}, Max: {}, Queue: {}",
                   corePoolSize, maxPoolSize, 2000);

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
