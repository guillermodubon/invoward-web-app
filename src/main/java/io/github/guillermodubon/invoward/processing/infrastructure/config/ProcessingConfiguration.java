package io.github.guillermodubon.invoward.processing.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProcessingProperties.class)
public class ProcessingConfiguration {

    public static final String PROCESSING_TASK_EXECUTOR = "processingTaskExecutor";
    private static final String THREAD_NAME_PREFIX = "invoward-processing-";

    @Bean(name = PROCESSING_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor processingTaskExecutor(ProcessingProperties properties) {
        ThreadPoolTaskExecutor executor = new ProcessingTaskExecutor();
        executor.setCorePoolSize(properties.executor().corePoolSize());
        executor.setMaxPoolSize(properties.executor().maxPoolSize());
        executor.setQueueCapacity(properties.executor().queueCapacity());
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(toAwaitTerminationSeconds(properties.shutdownAwait()));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    private static int toAwaitTerminationSeconds(Duration shutdownAwait) {
        long seconds = shutdownAwait.getSeconds();
        if (shutdownAwait.getNano() > 0) {
            seconds++;
        }
        return Math.toIntExact(seconds);
    }
}
