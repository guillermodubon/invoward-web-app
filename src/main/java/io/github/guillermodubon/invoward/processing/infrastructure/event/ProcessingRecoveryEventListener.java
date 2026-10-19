package io.github.guillermodubon.invoward.processing.infrastructure.event;

import io.github.guillermodubon.invoward.processing.application.service.RecoverProcessingJobsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Starts durable job recovery once the application is ready to accept work. */
@Component
public final class ProcessingRecoveryEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingRecoveryEventListener.class);

    private final RecoverProcessingJobsService recoveryService;

    public ProcessingRecoveryEventListener(RecoverProcessingJobsService recoveryService) {
        this.recoveryService = Objects.requireNonNull(recoveryService);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        try {
            recoveryService.recoverAtStartup();
        } catch (RuntimeException recoveryFailure) {
            // Recovery is best-effort at readiness; durable rows remain eligible on the next startup.
            LOGGER.warn("operation=processing_startup_recovery result=failure");
        }
    }
}
