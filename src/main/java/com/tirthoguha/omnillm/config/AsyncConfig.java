package com.tirthoguha.omnillm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Defines the thread pool used to drive Server-Sent Event streams. A managed
 * {@link ThreadPoolTaskExecutor} bean replaces the previous per-request
 * {@code Executors.newSingleThreadExecutor()} — which leaked a thread per stream and was never
 * shut down. As a Spring bean it is bounded, named for observability, and drained gracefully on
 * application shutdown (it implements {@code DisposableBean}).
 */
@Configuration
public class AsyncConfig {

    public static final String STREAM_EXECUTOR = "llmStreamExecutor";

    @Bean(STREAM_EXECUTOR)
    public ThreadPoolTaskExecutor llmStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("llm-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
