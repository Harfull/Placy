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

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = "fileProcessingExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int threads = Math.min(4, Math.max(2, availableProcessors / 4));

        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("FastProcessor-");
        executor.setKeepAliveSeconds(30);
        executor.setAllowCoreThreadTimeOut(false);

        executor.setRejectedExecutionHandler((r, executor1) -> {
            if (!executor1.isShutdown()) {
                r.run();
            }
        });

        executor.initialize();

        logger.info("High-performance executor initialized: {} threads, {} processors available",
                   threads, availableProcessors);
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
