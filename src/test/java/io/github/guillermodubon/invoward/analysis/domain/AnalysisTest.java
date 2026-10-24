package io.github.guillermodubon.invoward.analysis.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final PriceTolerance EXACT = PriceTolerance.exactMatch();

    @Test
    void registeredAnalysisStartsInTheApprovedEmptyState() {
        Analysis analysis = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW);

        assertEquals(AnalysisStatus.CREATED, analysis.status());
        assertEquals(AnalysisReviewStatus.PENDING, analysis.reviewStatus());
        assertNull(analysis.reviewedAt());
        assertNull(analysis.reconciliationStatus());
        assertNull(analysis.supplierName());
        assertNull(analysis.supplierKey());
        assertNull(analysis.referenceType());
        assertNull(analysis.referenceNumber());
        assertNull(analysis.invoiceNumber());
        assertNull(analysis.currency());
        assertNull(analysis.referenceTotal());
        assertNull(analysis.invoicedTotal());
        assertNull(analysis.difference());
        assertEquals(EXACT, analysis.priceTolerance());
        assertFalse(analysis.retryable());
        assertNull(analysis.failureCode());
        assertNull(analysis.failureUserMessage());
        assertEquals(0, analysis.version());
        assertNull(analysis.completedAt());
        assertNull(analysis.expiresAt());
        assertEquals(NOW, analysis.createdAt());
        assertEquals(NOW, analysis.updatedAt());
    }

    @Test
    void guestAnalysisUsesItsOwnersFixedExpiry() {
        Instant expiry = NOW.plusSeconds(86_400);
        GuestSessionOwner owner = GuestSessionOwner.create(UUID.randomUUID(), expiry, NOW);

        Analysis analysis = Analysis.create(UUID.randomUUID(), owner, EXACT, NOW);

        assertEquals(expiry, analysis.expiresAt());
        assertEquals(owner, analysis.owner());
    }

    @Test
    void transitionsCreatedAndUploadingAnalysisToUploading() {
        Analysis created = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW);
        Instant firstUploadAt = NOW.plusSeconds(5);

        Analysis uploading = created.transitionToUploading(firstUploadAt);
        Analysis uploadingAgain = uploading.transitionToUploading(firstUploadAt.plusSeconds(5));

        assertEquals(AnalysisStatus.UPLOADING, uploading.status());
        assertEquals(firstUploadAt, uploading.updatedAt());
        assertEquals(AnalysisStatus.UPLOADING, uploadingAgain.status());
        assertEquals(firstUploadAt.plusSeconds(5), uploadingAgain.updatedAt());
        assertEquals(created.id(), uploading.id());
        assertEquals(created.owner(), uploading.owner());
        assertEquals(created.priceTolerance(), uploading.priceTolerance());
        assertEquals(created.version(), uploading.version());
        assertEquals(created.createdAt(), uploading.createdAt());
    }

    @Test
    void transitionsUploadingAnalysisToClassifyingOnly() {
        Analysis uploading = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW)
                .transitionToUploading(NOW.plusSeconds(1));

        Analysis classifying = uploading.markClassifying(NOW.plusSeconds(2));

        assertEquals(AnalysisStatus.CLASSIFYING, classifying.status());
        assertEquals(uploading.version(), classifying.version());
        assertEquals(uploading.retryable(), classifying.retryable());
        assertEquals(uploading.createdAt(), classifying.createdAt());
        assertEquals(NOW.plusSeconds(2), classifying.updatedAt());
        assertThrows(IllegalStateException.class, () -> classifying.markClassifying(NOW.plusSeconds(3)));
    }

    @Test
    void beginsClassificationAndExtractionOnlyFromTheirLegalPredecessors() {
        Analysis uploading = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW)
                .transitionToUploading(NOW.plusSeconds(1));

        Analysis classifying = uploading.beginClassification(NOW.plusSeconds(2));
        Analysis extracting = classifying.beginExtraction(NOW.plusSeconds(3));

        assertEquals(AnalysisStatus.CLASSIFYING, classifying.status());
        assertEquals(AnalysisStatus.EXTRACTING, extracting.status());
        assertEquals(NOW.plusSeconds(2), classifying.updatedAt());
        assertEquals(NOW.plusSeconds(3), extracting.updatedAt());
        assertFalse(extracting.retryable());
        assertNull(extracting.failureCode());
        assertNull(extracting.failureUserMessage());
        assertNull(extracting.completedAt());
        assertThrows(IllegalStateException.class, () -> uploading.beginExtraction(NOW.plusSeconds(2)));
        assertThrows(IllegalStateException.class, () -> extracting.beginClassification(NOW.plusSeconds(4)));
        assertThrows(IllegalArgumentException.class, () -> uploading.beginClassification(NOW));
    }

    @Test
    void transitionsClassifyingAnalysisToAwaitingHumanExtractionConfirmation() {
        Analysis classifying = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW)
                .transitionToUploading(NOW.plusSeconds(1))
                .markClassifying(NOW.plusSeconds(2));

        Analysis awaitingConfirmation = classifying.awaitExtractionConfirmation(NOW.plusSeconds(3));

        assertEquals(AnalysisStatus.AWAITING_CONFIRMATION, awaitingConfirmation.status());
        assertEquals(classifying.id(), awaitingConfirmation.id());
        assertEquals(classifying.owner(), awaitingConfirmation.owner());
        assertEquals(classifying.version(), awaitingConfirmation.version());
        assertEquals(classifying.priceTolerance(), awaitingConfirmation.priceTolerance());
        assertEquals(classifying.reviewStatus(), awaitingConfirmation.reviewStatus());
        assertFalse(awaitingConfirmation.retryable());
        assertNull(awaitingConfirmation.failureCode());
        assertNull(awaitingConfirmation.failureUserMessage());
        assertNull(awaitingConfirmation.completedAt());
        assertEquals(NOW.plusSeconds(3), awaitingConfirmation.updatedAt());
        assertThrows(IllegalStateException.class,
                () -> awaitingConfirmation.awaitExtractionConfirmation(NOW.plusSeconds(4)));
        assertThrows(IllegalArgumentException.class,
                () -> classifying.awaitExtractionConfirmation(NOW.plusSeconds(1)));
    }

    @Test
    void successfulExtractionCanMoveToHumanConfirmation() {
        Analysis extracting = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW)
                .transitionToUploading(NOW.plusSeconds(1))
                .beginClassification(NOW.plusSeconds(2))
                .beginExtraction(NOW.plusSeconds(3));

        Analysis awaitingConfirmation = extracting.awaitExtractionConfirmation(NOW.plusSeconds(4));

        assertEquals(AnalysisStatus.AWAITING_CONFIRMATION, awaitingConfirmation.status());
        assertFalse(awaitingConfirmation.retryable());
        assertNull(awaitingConfirmation.failureCode());
        assertNull(awaitingConfirmation.failureUserMessage());
        assertThrows(IllegalStateException.class,
                () -> awaitingConfirmation.awaitExtractionConfirmation(NOW.plusSeconds(5)));
    }

    @Test
    void processingFailureStoresOnlySafeClassifiedFieldsAtTheExpectedMachineStage() {
        Analysis extracting = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW)
                .transitionToUploading(NOW.plusSeconds(1))
                .beginClassification(NOW.plusSeconds(2))
                .beginExtraction(NOW.plusSeconds(3));

        Analysis failed = extracting.failProcessing(
                AnalysisStatus.EXTRACTING, "AI_PROVIDER_UNAVAILABLE", "Extraction is temporarily unavailable.",
                true, NOW.plusSeconds(4));

        assertEquals(AnalysisStatus.FAILED, failed.status());
        assertTrue(failed.retryable());
        assertEquals("AI_PROVIDER_UNAVAILABLE", failed.failureCode());
        assertEquals("Extraction is temporarily unavailable.", failed.failureUserMessage());
        assertNull(failed.completedAt());
        assertEquals(NOW.plusSeconds(4), failed.updatedAt());
        assertEquals(extracting.referenceTotal(), failed.referenceTotal());
        assertThrows(IllegalStateException.class, () -> extracting.failProcessing(
                AnalysisStatus.CLASSIFYING, "AI_PROVIDER_UNAVAILABLE", "Safe message", true, NOW.plusSeconds(4)));
        assertThrows(IllegalStateException.class, () -> failed.failProcessing(
                AnalysisStatus.EXTRACTING, "AI_PROVIDER_UNAVAILABLE", "Safe message", true, NOW.plusSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> extracting.failProcessing(
                AnalysisStatus.EXTRACTING, "provider details", "Safe message", true, NOW.plusSeconds(4)));
        assertThrows(IllegalArgumentException.class, () -> extracting.failProcessing(
                AnalysisStatus.EXTRACTING, "AI_PROVIDER_UNAVAILABLE", "unsafe\nmessage", true, NOW.plusSeconds(4)));
        assertThrows(IllegalArgumentException.class, () -> extracting.failProcessing(
                AnalysisStatus.EXTRACTING, "AI_PROVIDER_UNAVAILABLE", "Safe message", true, NOW.plusSeconds(2)));
    }

    @Test
    void retryReturnsFailedAnalysisToThePersistedMachineStageAndClearsFailureState() {
        Analysis failed = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW)
                .transitionToUploading(NOW.plusSeconds(1))
                .beginClassification(NOW.plusSeconds(2))
                .beginExtraction(NOW.plusSeconds(3))
                .failProcessing(AnalysisStatus.EXTRACTING, "EXTRACTION_FAILED", "Extraction failed safely.",
                        true, NOW.plusSeconds(4));

        Analysis retried = failed.retryProcessing(AnalysisStatus.EXTRACTING, NOW.plusSeconds(5));

        assertEquals(AnalysisStatus.EXTRACTING, retried.status());
        assertFalse(retried.retryable());
        assertNull(retried.failureCode());
        assertNull(retried.failureUserMessage());
        assertNull(retried.completedAt());
        assertEquals(failed.owner(), retried.owner());
        assertEquals(failed.priceTolerance(), retried.priceTolerance());
        assertEquals(failed.expiresAt(), retried.expiresAt());
        assertEquals(NOW.plusSeconds(5), retried.updatedAt());
        assertThrows(IllegalStateException.class,
                () -> retried.retryProcessing(AnalysisStatus.EXTRACTING, NOW.plusSeconds(6)));
        assertThrows(IllegalStateException.class,
                () -> failed.retryProcessing(AnalysisStatus.UPLOADING, NOW.plusSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> failed.retryProcessing(AnalysisStatus.EXTRACTING, NOW.plusSeconds(3)));

        Analysis terminalFailure = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW)
                .transitionToUploading(NOW.plusSeconds(1))
                .beginClassification(NOW.plusSeconds(2))
                .failProcessing(AnalysisStatus.CLASSIFYING, "CLASSIFICATION_FAILED", "Classification failed safely.",
                        false, NOW.plusSeconds(3));
        assertThrows(IllegalStateException.class,
                () -> terminalFailure.retryProcessing(AnalysisStatus.CLASSIFYING, NOW.plusSeconds(4)));
    }

    @Test
    void confirmationCopiesReviewedSummaryAndMovesAnalysisToMatchingWithoutCalculatingDifference() {
        Analysis awaiting = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW)
                .transitionToUploading(NOW.plusSeconds(1))
                .markClassifying(NOW.plusSeconds(2))
                .awaitExtractionConfirmation(NOW.plusSeconds(3));

        Analysis matching = awaiting.confirmExtraction(
                "PURCHASE_ORDER", "PO-1", "INV-1", "  Acme\u00a0  Parts  ", "USD",
                new BigDecimal("100.0000"), new BigDecimal("115.0000"), NOW.plusSeconds(4));

        assertEquals(AnalysisStatus.MATCHING, matching.status());
        assertEquals(AnalysisReviewStatus.PENDING, matching.reviewStatus());
        assertNull(matching.reconciliationStatus());
        assertEquals("PURCHASE_ORDER", matching.referenceType());
        assertEquals("PO-1", matching.referenceNumber());
        assertEquals("INV-1", matching.invoiceNumber());
        assertEquals("  Acme\u00a0  Parts  ", matching.supplierName());
        assertEquals("acme parts", matching.supplierKey());
        assertEquals("USD", matching.currency());
        assertEquals(new BigDecimal("100.0000"), matching.referenceTotal());
        assertEquals(new BigDecimal("115.0000"), matching.invoicedTotal());
        assertNull(matching.difference());
        assertFalse(matching.retryable());
        assertNull(matching.completedAt());
        assertEquals(NOW.plusSeconds(4), matching.updatedAt());
        assertThrows(IllegalStateException.class, () -> matching.confirmExtraction(
                "QUOTE", null, null, null, null, null, null, NOW.plusSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> awaiting.confirmExtraction(
                "QUOTE", null, null, null, null, null, null, NOW.plusSeconds(2)));
    }

    @Test
    void matchingCanWaitForMatchReviewWithoutChangingReconciliationData() {
        Analysis matching = matchingAnalysis();
        Instant reviewAt = NOW.plusSeconds(5);

        Analysis awaitingReview = matching.awaitMatchReview(reviewAt);

        assertEquals(AnalysisStatus.AWAITING_MATCH_REVIEW, awaitingReview.status());
        assertEquals(matching.id(), awaitingReview.id());
        assertEquals(matching.owner(), awaitingReview.owner());
        assertEquals(matching.reviewStatus(), awaitingReview.reviewStatus());
        assertNull(awaitingReview.reconciliationStatus());
        assertEquals(matching.supplierName(), awaitingReview.supplierName());
        assertEquals(matching.supplierKey(), awaitingReview.supplierKey());
        assertEquals(matching.referenceTotal(), awaitingReview.referenceTotal());
        assertEquals(matching.invoicedTotal(), awaitingReview.invoicedTotal());
        assertNull(awaitingReview.difference());
        assertFalse(awaitingReview.retryable());
        assertNull(awaitingReview.completedAt());
        assertEquals(matching.createdAt(), awaitingReview.createdAt());
        assertEquals(reviewAt, awaitingReview.updatedAt());
        assertThrows(IllegalStateException.class,
                () -> awaitingReview.awaitMatchReview(reviewAt.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> matching.awaitMatchReview(matching.updatedAt().minusSeconds(1)));
    }

    @Test
    void matchingAndReviewedMatchesCanAdvanceToReconciliationWithoutCalculatingDifferences() {
        Analysis matching = matchingAnalysis();
        Instant transitionAt = NOW.plusSeconds(5);

        Analysis automaticReconciliation = matching.beginReconciliation(transitionAt);
        Analysis reviewedReconciliation = matching.awaitMatchReview(transitionAt)
                .beginReconciliation(transitionAt.plusSeconds(1));

        for (Analysis reconciling : new Analysis[]{automaticReconciliation, reviewedReconciliation}) {
            assertEquals(AnalysisStatus.RECONCILING, reconciling.status());
            assertEquals(AnalysisReviewStatus.PENDING, reconciling.reviewStatus());
            assertNull(reconciling.reconciliationStatus());
            assertNull(reconciling.difference());
            assertFalse(reconciling.retryable());
            assertNull(reconciling.completedAt());
        }
        assertEquals(transitionAt, automaticReconciliation.updatedAt());
        assertEquals(transitionAt.plusSeconds(1), reviewedReconciliation.updatedAt());
        assertThrows(IllegalStateException.class,
                () -> reviewedReconciliation.beginReconciliation(transitionAt.plusSeconds(2)));
        Analysis created = Analysis.create(UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW);
        assertThrows(IllegalStateException.class,
                () -> created.beginReconciliation(NOW.plusSeconds(1)));
    }

    @Test
    void completesReconciliationWithOriginalResultAndSignedDifference() {
        Analysis reconciling = matchingAnalysis().beginReconciliation(NOW.plusSeconds(5));
        Instant completedAt = NOW.plusSeconds(6);

        Analysis completed = reconciling.completeReconciliation(
                AnalysisReconciliationStatus.REVIEW_REQUIRED, new BigDecimal("-15.2500"), completedAt);

        assertEquals(AnalysisStatus.GENERATING_REPORT, completed.status());
        assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, completed.reconciliationStatus());
        assertEquals(new BigDecimal("-15.2500"), completed.difference());
        assertEquals(reconciling.id(), completed.id());
        assertEquals(reconciling.owner(), completed.owner());
        assertEquals(reconciling.reviewStatus(), completed.reviewStatus());
        assertEquals(reconciling.supplierName(), completed.supplierName());
        assertEquals(reconciling.supplierKey(), completed.supplierKey());
        assertEquals(reconciling.referenceType(), completed.referenceType());
        assertEquals(reconciling.referenceNumber(), completed.referenceNumber());
        assertEquals(reconciling.invoiceNumber(), completed.invoiceNumber());
        assertEquals(reconciling.currency(), completed.currency());
        assertEquals(reconciling.referenceTotal(), completed.referenceTotal());
        assertEquals(reconciling.invoicedTotal(), completed.invoicedTotal());
        assertEquals(reconciling.priceTolerance(), completed.priceTolerance());
        assertEquals(reconciling.retryable(), completed.retryable());
        assertEquals(reconciling.version(), completed.version());
        assertEquals(reconciling.completedAt(), completed.completedAt());
        assertEquals(reconciling.expiresAt(), completed.expiresAt());
        assertEquals(reconciling.createdAt(), completed.createdAt());
        assertEquals(completedAt, completed.updatedAt());

        Analysis matched = reconciling.completeReconciliation(
                AnalysisReconciliationStatus.MATCHED, null, completedAt);
        assertEquals(AnalysisReconciliationStatus.MATCHED, matched.reconciliationStatus());
        assertNull(matched.difference());
    }

    @Test
    void completesReportGenerationAndPreservesReconciliationSnapshot() {
        Analysis before = matchingAnalysis().beginReconciliation(NOW.plusSeconds(5))
                .completeReconciliation(AnalysisReconciliationStatus.REVIEW_REQUIRED,
                        new BigDecimal("-15.2500"), NOW.plusSeconds(6));
        Instant generatedAt = NOW.plusSeconds(7);

        Analysis completed = before.completeReportGeneration(generatedAt);

        assertEquals(AnalysisStatus.COMPLETED, completed.status());
        assertEquals(generatedAt, completed.completedAt());
        assertEquals(generatedAt, completed.updatedAt());
        assertFalse(completed.retryable());
        assertEquals(before.id(), completed.id());
        assertEquals(before.owner(), completed.owner());
        assertEquals(before.reviewStatus(), completed.reviewStatus());
        assertEquals(before.reviewedAt(), completed.reviewedAt());
        assertEquals(before.reconciliationStatus(), completed.reconciliationStatus());
        assertEquals(before.supplierName(), completed.supplierName());
        assertEquals(before.supplierKey(), completed.supplierKey());
        assertEquals(before.referenceType(), completed.referenceType());
        assertEquals(before.referenceNumber(), completed.referenceNumber());
        assertEquals(before.invoiceNumber(), completed.invoiceNumber());
        assertEquals(before.currency(), completed.currency());
        assertEquals(before.referenceTotal(), completed.referenceTotal());
        assertEquals(before.invoicedTotal(), completed.invoicedTotal());
        assertEquals(before.difference(), completed.difference());
        assertEquals(before.priceTolerance(), completed.priceTolerance());
        assertEquals(before.version(), completed.version());
        assertEquals(before.expiresAt(), completed.expiresAt());
        assertEquals(before.createdAt(), completed.createdAt());
    }

    @Test
    void completedRegisteredAnalysisCanBeMarkedReviewedAndReverted() {
        Analysis completed = completedAnalysis();
        Instant reviewedAt = completed.updatedAt().plusSeconds(1);

        Analysis reviewed = completed.changeReviewStatus(AnalysisReviewStatus.REVIEWED, reviewedAt);

        assertEquals(AnalysisReviewStatus.REVIEWED, reviewed.reviewStatus());
        assertEquals(reviewedAt, reviewed.reviewedAt());
        assertEquals(reviewedAt, reviewed.updatedAt());
        assertEquals(completed.completedAt(), reviewed.completedAt());
        assertEquals(completed.version(), reviewed.version());

        Instant revertedAt = reviewedAt.plusSeconds(1);
        Analysis pending = reviewed.changeReviewStatus(AnalysisReviewStatus.PENDING, revertedAt);

        assertEquals(AnalysisReviewStatus.PENDING, pending.reviewStatus());
        assertNull(pending.reviewedAt());
        assertEquals(revertedAt, pending.updatedAt());
        assertEquals(completed.completedAt(), pending.completedAt());
        assertEquals(completed.version(), pending.version());
    }

    @Test
    void reviewStatusSameValueIsNoOpAndLegacyReviewedNullTimestampRemainsUnknown() {
        Analysis completed = completedAnalysis();

        assertSame(completed, completed.changeReviewStatus(
                AnalysisReviewStatus.PENDING, completed.updatedAt().plusSeconds(10)));
        Analysis legacyReviewed = withReviewState(
                completed, AnalysisReviewStatus.REVIEWED, null, completed.updatedAt());

        assertSame(legacyReviewed, legacyReviewed.changeReviewStatus(
                AnalysisReviewStatus.REVIEWED, completed.updatedAt().plusSeconds(10)));
        assertNull(legacyReviewed.reviewedAt());
        assertEquals(completed.updatedAt(), legacyReviewed.updatedAt());
    }

    @Test
    void reviewTimestampIsPreservedByTheExistingProcessingStateMachine() {
        Instant legacyReviewTime = NOW.plusSeconds(1);
        Analysis reviewed = withReviewState(
                Analysis.create(UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW),
                AnalysisReviewStatus.REVIEWED, legacyReviewTime, legacyReviewTime);

        Analysis completed = reviewed.transitionToUploading(NOW.plusSeconds(2))
                .beginClassification(NOW.plusSeconds(3))
                .beginExtraction(NOW.plusSeconds(4))
                .awaitExtractionConfirmation(NOW.plusSeconds(5))
                .confirmExtraction("PURCHASE_ORDER", "PO-1", "INV-1", "Acme Parts", "USD",
                        BigDecimal.TEN, BigDecimal.TEN, NOW.plusSeconds(6))
                .awaitMatchReview(NOW.plusSeconds(7))
                .beginReconciliation(NOW.plusSeconds(8))
                .completeReconciliation(AnalysisReconciliationStatus.MATCHED, BigDecimal.ZERO, NOW.plusSeconds(9))
                .completeReportGeneration(NOW.plusSeconds(10));

        assertEquals(AnalysisStatus.COMPLETED, completed.status());
        assertEquals(AnalysisReviewStatus.REVIEWED, completed.reviewStatus());
        assertEquals(legacyReviewTime, completed.reviewedAt());
    }

    @Test
    void reviewStatusChangeRequiresCompletedRegisteredAnalysisAndValidArguments() {
        Analysis incomplete = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW);
        Analysis guest = Analysis.create(
                UUID.randomUUID(), GuestSessionOwner.create(UUID.randomUUID(), NOW.plusSeconds(100), NOW), EXACT, NOW);

        assertThrows(IllegalStateException.class,
                () -> incomplete.changeReviewStatus(AnalysisReviewStatus.REVIEWED, NOW.plusSeconds(1)));
        assertThrows(IllegalStateException.class,
                () -> guest.changeReviewStatus(AnalysisReviewStatus.REVIEWED, NOW.plusSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> completedAnalysis().changeReviewStatus(null, NOW.plusSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> completedAnalysis().changeReviewStatus(AnalysisReviewStatus.REVIEWED, null));
        assertThrows(IllegalArgumentException.class,
                () -> completedAnalysis().changeReviewStatus(AnalysisReviewStatus.REVIEWED, NOW));
    }

    @Test
    void reviewedAtMustFitReviewStatusAndAggregateTimestampRange() {
        Analysis completed = completedAnalysis();

        assertThrows(IllegalArgumentException.class,
                () -> withReviewState(completed, AnalysisReviewStatus.PENDING,
                        completed.updatedAt(), completed.updatedAt()));
        assertThrows(IllegalArgumentException.class,
                () -> withReviewState(completed, AnalysisReviewStatus.REVIEWED,
                        completed.createdAt().minusSeconds(1), completed.updatedAt()));
        assertThrows(IllegalArgumentException.class,
                () -> withReviewState(completed, AnalysisReviewStatus.REVIEWED,
                        completed.updatedAt().plusSeconds(1), completed.updatedAt()));
    }

    @Test
    void rejectsReportCompletionOutsideGeneratingReportOrWithInvalidTime() {
        Analysis generatingReport = matchingAnalysis().beginReconciliation(NOW.plusSeconds(5))
                .completeReconciliation(AnalysisReconciliationStatus.MATCHED, null, NOW.plusSeconds(6));

        assertThrows(NullPointerException.class, () -> generatingReport.completeReportGeneration(null));
        assertThrows(IllegalArgumentException.class,
                () -> generatingReport.completeReportGeneration(generatingReport.updatedAt().minusSeconds(1)));
        assertThrows(IllegalStateException.class,
                () -> matchingAnalysis().completeReportGeneration(NOW.plusSeconds(1)));
        assertThrows(IllegalStateException.class,
                () -> generatingReport.completeReportGeneration(NOW.plusSeconds(7))
                        .completeReportGeneration(NOW.plusSeconds(8)));
    }

    @Test
    void rejectsReconciliationCompletionOutsideReconcilingStateOrWithInvalidArguments() {
        Analysis reconciling = matchingAnalysis().beginReconciliation(NOW.plusSeconds(5));

        assertThrows(IllegalStateException.class,
                () -> matchingAnalysis().completeReconciliation(
                        AnalysisReconciliationStatus.MATCHED, BigDecimal.ZERO, NOW.plusSeconds(6)));
        assertThrows(NullPointerException.class,
                () -> reconciling.completeReconciliation(null, BigDecimal.ZERO, NOW.plusSeconds(6)));
        assertThrows(NullPointerException.class,
                () -> reconciling.completeReconciliation(AnalysisReconciliationStatus.MATCHED, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> reconciling.completeReconciliation(
                        AnalysisReconciliationStatus.MATCHED, BigDecimal.ZERO, reconciling.updatedAt().minusSeconds(1)));
    }

    @Test
    void rejectsUploadTransitionsAfterAnalysisLeavesUploadStates() {
        Analysis created = Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW);

        for (AnalysisStatus status : new AnalysisStatus[]{
                AnalysisStatus.CLASSIFYING, AnalysisStatus.EXTRACTING, AnalysisStatus.COMPLETED,
                AnalysisStatus.FAILED}) {
            Analysis laterState = withStatus(created, status);
            assertThrows(IllegalStateException.class,
                    () -> laterState.transitionToUploading(NOW.plusSeconds(1)));
        }
    }

    @Test
    void rejectsInvalidRequiredValuesOwnershipExpiryAndTimestamps() {
        RegisteredUserOwner registered = new RegisteredUserOwner(UUID.randomUUID());
        GuestSessionOwner guest = GuestSessionOwner.create(UUID.randomUUID(), NOW.plusSeconds(10), NOW);

        assertThrows(NullPointerException.class, () -> Analysis.create(null, registered, EXACT, NOW));
        assertThrows(NullPointerException.class, () -> Analysis.create(UUID.randomUUID(), null, EXACT, NOW));
        assertThrows(NullPointerException.class, () -> Analysis.create(UUID.randomUUID(), registered, null, NOW));
        assertThrows(NullPointerException.class, () -> Analysis.create(UUID.randomUUID(), registered, EXACT, null));
        assertThrows(NullPointerException.class,
                () -> validAnalysis(guest, null, NOW, NOW, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(guest, NOW.plusSeconds(11), NOW, NOW, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(registered, NOW.plusSeconds(10), NOW, NOW, null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(registered, null, NOW, NOW.minusSeconds(1), null, null, null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(registered, null, NOW, NOW, NOW.plusSeconds(1), null, null, null, 0));
    }

    @Test
    void rejectsNegativeTotalsAndVersionButAllowsSignedDifference() {
        RegisteredUserOwner owner = new RegisteredUserOwner(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(owner, null, NOW, NOW, null, new BigDecimal("-0.01"), null, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(owner, null, NOW, NOW, null, null, new BigDecimal("-0.01"), null, 0));
        Analysis negativeDifference = validAnalysis(
                owner, null, NOW, NOW, null, null, null, new BigDecimal("-0.01"), 0);
        assertEquals(new BigDecimal("-0.01"), negativeDifference.difference());
        assertThrows(IllegalArgumentException.class,
                () -> validAnalysis(owner, null, NOW, NOW, null, null, null, null, -1));
    }

    @Test
    void ownerCannotLeakGuestCredentialThroughAnalysisString() {
        UUID guestId = UUID.randomUUID();
        GuestSessionOwner owner = GuestSessionOwner.create(guestId, NOW.plusSeconds(10), NOW);

        assertFalse(Analysis.create(UUID.randomUUID(), owner, EXACT, NOW).toString().contains(guestId.toString()));
        assertTrue(Analysis.create(UUID.randomUUID(), owner, EXACT, NOW).toString().contains("status=CREATED"));
    }

    private static Analysis validAnalysis(
            AnalysisOwner owner,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            BigDecimal referenceTotal,
            BigDecimal invoicedTotal,
            BigDecimal difference,
            long version) {
        return new Analysis(
                UUID.randomUUID(), owner, AnalysisStatus.CREATED, AnalysisReviewStatus.PENDING, null,
                null, null, null, null, null, null, null,
                referenceTotal, invoicedTotal, difference, EXACT,
                false, null, null, version, completedAt, expiresAt, createdAt, updatedAt);
    }

    private static Analysis matchingAnalysis() {
        return Analysis.create(UUID.randomUUID(), new RegisteredUserOwner(UUID.randomUUID()), EXACT, NOW)
                .transitionToUploading(NOW.plusSeconds(1))
                .markClassifying(NOW.plusSeconds(2))
                .awaitExtractionConfirmation(NOW.plusSeconds(3))
                .confirmExtraction("PURCHASE_ORDER", "PO-1", "INV-1", "Acme Parts", "USD",
                        new BigDecimal("100.0000"), new BigDecimal("100.0000"), NOW.plusSeconds(4));
    }

    private static Analysis completedAnalysis() {
        return matchingAnalysis()
                .beginReconciliation(NOW.plusSeconds(5))
                .completeReconciliation(AnalysisReconciliationStatus.MATCHED, BigDecimal.ZERO, NOW.plusSeconds(6))
                .completeReportGeneration(NOW.plusSeconds(7));
    }

    private static Analysis withReviewState(
            Analysis source,
            AnalysisReviewStatus reviewStatus,
            Instant reviewedAt,
            Instant updatedAt) {
        return new Analysis(
                source.id(), source.owner(), source.status(), reviewStatus, reviewedAt,
                source.reconciliationStatus(), source.supplierName(), source.supplierKey(),
                source.referenceType(), source.referenceNumber(), source.invoiceNumber(), source.currency(),
                source.referenceTotal(), source.invoicedTotal(), source.difference(), source.priceTolerance(),
                source.retryable(), source.failureCode(), source.failureUserMessage(), source.version(),
                source.completedAt(), source.expiresAt(), source.createdAt(), updatedAt);
    }

    private static Analysis withStatus(Analysis analysis, AnalysisStatus status) {
        return new Analysis(
                analysis.id(), analysis.owner(), status, analysis.reviewStatus(),
                analysis.reviewedAt(), analysis.reconciliationStatus(), analysis.supplierName(), analysis.supplierKey(),
                analysis.referenceType(), analysis.referenceNumber(), analysis.invoiceNumber(),
                analysis.currency(), analysis.referenceTotal(), analysis.invoicedTotal(),
                analysis.difference(), analysis.priceTolerance(), analysis.retryable(),
                analysis.failureCode(), analysis.failureUserMessage(), analysis.version(),
                analysis.completedAt(), analysis.expiresAt(), analysis.createdAt(), analysis.updatedAt());
    }
}
