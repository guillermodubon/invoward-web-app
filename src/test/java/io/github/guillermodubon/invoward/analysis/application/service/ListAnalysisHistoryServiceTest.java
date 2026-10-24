package io.github.guillermodubon.invoward.analysis.application.service;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryPage;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryQuery;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisHistoryQueryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListAnalysisHistoryServiceTest {

    @Test
    void delegatesOnlyTheAuthenticatedRegisteredUserIdAndTypedQuery() {
        AnalysisHistoryQueryRepository repository = mock(AnalysisHistoryQueryRepository.class);
        ListAnalysisHistoryService service = new ListAnalysisHistoryService(repository);
        UUID registeredUserId = UUID.randomUUID();
        AnalysisHistoryQuery query = AnalysisHistoryQuery.defaults();
        AnalysisHistoryPage expected = new AnalysisHistoryPage(List.of(), 0, 20, 0);
        when(repository.findForRegisteredUser(registeredUserId, query)).thenReturn(expected);

        AnalysisHistoryPage actual = service.list(registeredUserId, query);

        assertSame(expected, actual);
        verify(repository).findForRegisteredUser(registeredUserId, query);
    }

    @Test
    void rejectsMissingRegisteredIdentityBeforeCallingRepository() {
        AnalysisHistoryQueryRepository repository = mock(AnalysisHistoryQueryRepository.class);
        ListAnalysisHistoryService service = new ListAnalysisHistoryService(repository);

        assertThrows(NullPointerException.class, () -> service.list(null, AnalysisHistoryQuery.defaults()));
        verify(repository, never()).findForRegisteredUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsMissingQueryBeforeCallingRepository() {
        AnalysisHistoryQueryRepository repository = mock(AnalysisHistoryQueryRepository.class);
        ListAnalysisHistoryService service = new ListAnalysisHistoryService(repository);

        assertThrows(NullPointerException.class, () -> service.list(UUID.randomUUID(), null));
        verify(repository, never()).findForRegisteredUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
