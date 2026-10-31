package io.github.guillermodubon.invoward.sharing.application.service;

import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RevokeShareLinkServiceTest {

    private final RevokeShareLinkTransaction transaction = mock(RevokeShareLinkTransaction.class);
    private final RevokeShareLinkService service = new RevokeShareLinkService(transaction);

    @Test
    void delegatesParentAndLinkIdentityToTheTransactionBoundary() {
        UUID analysisId = UUID.randomUUID();
        UUID shareLinkId = UUID.randomUUID();
        RegisteredUserOwner owner = new RegisteredUserOwner(UUID.randomUUID());

        service.revoke(analysisId, owner, shareLinkId);

        verify(transaction).revoke(analysisId, owner, shareLinkId);
    }

    @Test
    void facadeDoesNotWrapTheAtomicTransactionBoundary() throws NoSuchMethodException {
        assertFalse(RevokeShareLinkService.class
                .getMethod("revoke", UUID.class,
                        io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner.class, UUID.class)
                .isAnnotationPresent(Transactional.class));
    }
}
