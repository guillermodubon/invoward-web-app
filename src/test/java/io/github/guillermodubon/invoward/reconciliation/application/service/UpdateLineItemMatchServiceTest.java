package io.github.guillermodubon.invoward.reconciliation.application.service;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.reconciliation.application.model.LineItemMatchSetView;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualLineItemMatchUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.model.ManualMatchAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class UpdateLineItemMatchServiceTest {

    private final UpdateLineItemMatchTransaction transaction = mock(UpdateLineItemMatchTransaction.class);
    private final GetLineItemMatchesService getService = mock(GetLineItemMatchesService.class);
    private final UpdateLineItemMatchService service = new UpdateLineItemMatchService(transaction, getService);

    @Test
    void commitsManualUpdateBeforeLoadingTheRefreshedMatchSet() {
        UUID analysisId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        AnalysisOwner owner = new RegisteredUserOwner(UUID.randomUUID());
        ManualLineItemMatchUpdate command = new ManualLineItemMatchUpdate(3, ManualMatchAction.CONFIRM, null, null);
        LineItemMatchSetView expected = new LineItemMatchSetView(
                analysisId, AnalysisStatus.AWAITING_MATCH_REVIEW, true, List.of());
        when(getService.get(analysisId, owner)).thenReturn(expected);

        LineItemMatchSetView result = service.update(analysisId, matchId, owner, command);

        assertSame(expected, result);
        var calls = inOrder(transaction, getService);
        calls.verify(transaction).update(analysisId, matchId, owner, command);
        calls.verify(getService).get(analysisId, owner);
        verifyNoMoreInteractions(transaction, getService);
    }
}
