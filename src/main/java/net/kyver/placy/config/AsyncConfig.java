package net.kyver.placy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int processors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(processors * 2);
        executor.setMaxPoolSize(processors * 4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("Placy-Async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.setKeepAliveSeconds(300);
        executor.setAllowCoreThreadTimeOut(true);

        executor.initialize();
        return executor;
    }

    @Bean(name = "fileProcessingExecutor")
    public Executor fileProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int processors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(processors * 3);
        executor.setMaxPoolSize(processors * 6);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("Placy-FileProcessor-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

        executor.setKeepAliveSeconds(600);
        executor.setAllowCoreThreadTimeOut(false);

        executor.initialize();
        return executor;
    }

    @Bean(name = "ioExecutor")
    public Executor ioExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int processors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(processors * 4);
        executor.setMaxPoolSize(processors * 8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("Placy-IO-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.setKeepAliveSeconds(180);
        executor.setAllowCoreThreadTimeOut(true);

        executor.initialize();
        return executor;
    }

    @Bean(name = "archiveProcessingExecutor")
    public Executor archiveProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int processors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(processors * 2);
        executor.setMaxPoolSize(processors * 4);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("Placy-Archive-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(90);

        executor.setKeepAliveSeconds(600);
        executor.setAllowCoreThreadTimeOut(false);

        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }
}
