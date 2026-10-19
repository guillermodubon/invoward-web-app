package io.github.guillermodubon.invoward.processing.infrastructure.event;

import io.github.guillermodubon.invoward.processing.application.port.ProcessingWakePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;

/** Delivers best-effort wake-ups only after the transaction has committed. */
@Component
public class ProcessingRequestedEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingRequestedEventListener.class);

    private final ProcessingWakePort wakePort;

    public ProcessingRequestedEventListener(ProcessingWakePort wakePort) {
        this.wakePort = Objects.requireNonNull(wakePort);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProcessingRequested(ProcessingRequestedEvent event) {
        try {
            wakePort.wake(event.analysisId());
        } catch (RuntimeException exception) {
            // A wake-up is best-effort; the committed job remains recoverable from PostgreSQL.
            LOGGER.warn("operation=processing_wake result=failure");
        }
    }
}
