package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryPage;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryQuery;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisHistoryQueryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Read-only registered-user history use case; the caller must supply its authenticated user ID. */
@Service
@Transactional(readOnly = true)
@ConditionalOnBean(AnalysisHistoryQueryRepository.class)
public class ListAnalysisHistoryService {

    private final AnalysisHistoryQueryRepository queryRepository;

    public ListAnalysisHistoryService(AnalysisHistoryQueryRepository queryRepository) {
        this.queryRepository = Objects.requireNonNull(queryRepository, "queryRepository must not be null");
    }

    /** Lists only the registered account identified by the authenticated principal, never a guest owner. */
    public AnalysisHistoryPage list(UUID registeredUserId, AnalysisHistoryQuery query) {
        Objects.requireNonNull(registeredUserId, "registeredUserId must not be null");
        Objects.requireNonNull(query, "query must not be null");
        return Objects.requireNonNull(
                queryRepository.findForRegisteredUser(registeredUserId, query),
                "queryRepository must return a history page");
    }
}
