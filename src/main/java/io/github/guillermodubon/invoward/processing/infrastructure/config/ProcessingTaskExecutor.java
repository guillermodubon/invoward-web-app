package io.github.guillermodubon.invoward.processing.infrastructure.config;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Drains accepted tasks on shutdown even when the Spring lifecycle previously paused the pool. */
final class ProcessingTaskExecutor extends ThreadPoolTaskExecutor {

    @Override
    public void shutdown() {
        // Workers can already be waiting in beforeExecute after a cached context was paused.
        // Release that lifecycle pause before waiting for accepted tasks to finish.
        super.start();
        super.shutdown();
    }
}
