package io.github.guillermodubon.invoward.analysis.application.port;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryPage;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryQuery;

import java.util.UUID;

/** Persistence port for a bounded, registered-user-only Analysis history page. */
public interface AnalysisHistoryQueryRepository {

    AnalysisHistoryPage findForRegisteredUser(UUID registeredUserId, AnalysisHistoryQuery query);
}
