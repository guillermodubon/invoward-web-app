package io.github.guillermodubon.invoward.reporting.application.port;

import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.model.PresignedReportDownload;

import java.time.Duration;

/** Provider-neutral private storage operations for generated PDF reports. */
public interface ReportStorage {

    /** Stores a validated report artifact at its server-generated storage key. */
    void store(String storageKey, GeneratedReportArtifact artifact);

    /** Deletes a report object, primarily to compensate for a failed database transaction. */
    void delete(String storageKey);

    /** Creates a short-lived attachment URL for an authorized report. */
    PresignedReportDownload createPresignedDownload(
            String storageKey,
            String downloadFilename,
            Duration ttl);
}
