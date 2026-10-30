package io.github.guillermodubon.invoward.sharing.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.application.service.DetectDocumentTypesService;
import io.github.guillermodubon.invoward.extraction.application.service.RunDocumentExtractionService;
import io.github.guillermodubon.invoward.processing.infrastructure.ProcessingWorker;
import io.github.guillermodubon.invoward.reconciliation.application.model.DiscrepancyResolutionUpdate;
import io.github.guillermodubon.invoward.reconciliation.application.port.DiscrepancyRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.application.port.ReconciliationLineRepository;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reconciliation.application.service.ReconcileAnalysisService;
import io.github.guillermodubon.invoward.reconciliation.application.service.RunLineItemMatchingService;
import io.github.guillermodubon.invoward.reconciliation.application.service.UpdateDiscrepancyService;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionReason;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyResolutionStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLineStatus;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.application.service.GenerateReportService;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.sharing.application.model.CreatedShareLink;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkExpiryChoice;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.application.service.CreateShareLinkService;
import io.github.guillermodubon.invoward.sharing.application.service.RevokeShareLinkService;
import io.github.guillermodubon.invoward.sharing.domain.ShareLink;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Rechecks active share capabilities against persisted review state without reprocessing the Analysis. */
@SpringBootTest(properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
@AutoConfigureMockMvc
@Import(ShareLinkRevisitWorkflowIT.MutableClockConfiguration.class)
class ShareLinkRevisitWorkflowIT {

    private static final Instant BASE_NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final String PRIVATE_REVIEW_NOTE = "Internal supplier follow-up note must remain private.";
    private static final String PUBLIC_NOT_FOUND =
            "{\"code\":\"SHARE_LINK_NOT_FOUND\",\"message\":\"Shared report was not found.\"}";
    private static final String PRIVATE_STORAGE_KEY = "share-revisit-report/";
    private static final String REPORT_HASH = "f".repeat(64);

    private final List<UUID> analysisIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ExtractedDocumentRepository extractedDocumentRepository;

    @Autowired
    private LineItemMatchRepository lineItemMatchRepository;

    @Autowired
    private ReconciliationLineRepository reconciliationLineRepository;

    @Autowired
    private DiscrepancyRepository discrepancyRepository;

    @Autowired
    private GeneratedReportRepository generatedReportRepository;

    @Autowired
    private ShareLinkRepository shareLinkRepository;

    @Autowired
    private CreateShareLinkService createShareLinkService;

    @Autowired
    private RevokeShareLinkService revokeShareLinkService;

    @Autowired
    private UpdateDiscrepancyService updateDiscrepancyService;

    @MockitoSpyBean
    private ProcessingWorker processingWorker;

    @MockitoSpyBean
    private GetReconciliationService getReconciliationService;

    @MockitoSpyBean
    private ReconcileAnalysisService reconcileAnalysisService;

    @MockitoSpyBean
    private GenerateReportService generateReportService;

    @MockitoSpyBean
    private DetectDocumentTypesService detectDocumentTypesService;

    @MockitoSpyBean
    private RunDocumentExtractionService runDocumentExtractionService;

    @MockitoSpyBean
    private RunLineItemMatchingService runLineItemMatchingService;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void cleanFixtures() {
        analysisIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.analyses WHERE id = ?", id));
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM invoward.users WHERE id = ?", id));
        analysisIds.clear();
        userIds.clear();
    }

    @Test
    void multipleLinksRevokeIndependentlyAndRevisitUsesCurrentPersistedResolutionWithoutReprocessing()
            throws Exception {
        AnalysisFixture fixture = createCompletedAnalysisWithPersistedReconciliation();
        CreatedShareLink firstLink = createShareLinkService.create(
                fixture.analysis().id(), fixture.analysis().owner(), ShareLinkExpiryChoice.DAYS_7);
        CreatedShareLink secondLink = createShareLinkService.create(
                fixture.analysis().id(), fixture.analysis().owner(), ShareLinkExpiryChoice.DAYS_7);
        String firstToken = rawToken(firstLink);
        String secondToken = rawToken(secondLink);
        assertNotEquals(firstToken, secondToken);

        AnalysisJob originalJob = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        GeneratedReport originalReport = generatedReportRepository.findByAnalysisId(fixture.analysis().id())
                .orElseThrow();
        long originalLinkCount = shareLinkRepository.countActiveByAnalysisId(
                fixture.analysis().id(), clock.instant());
        assertEquals(2, originalLinkCount);
        clearInvocations(processingWorker, getReconciliationService, reconcileAnalysisService,
                generateReportService, detectDocumentTypesService, runDocumentExtractionService,
                runLineItemMatchingService);

        JsonNode firstInitialRead = publicRead(firstToken, 200);
        JsonNode secondInitialRead = publicRead(secondToken, 200);
        assertEquals("OPEN", discrepancyStatus(firstInitialRead));
        assertEquals("OPEN", discrepancyStatus(secondInitialRead));

        Discrepancy accepted = updateDiscrepancyService.update(
                fixture.analysis().id(), fixture.analysis().owner(), fixture.discrepancyId(),
                new DiscrepancyResolutionUpdate(
                        0, DiscrepancyResolutionStatus.ACCEPTED,
                        DiscrepancyResolutionReason.OTHER, PRIVATE_REVIEW_NOTE));
        assertEquals(DiscrepancyResolutionStatus.ACCEPTED, accepted.resolutionStatus());
        assertEquals(PRIVATE_REVIEW_NOTE, accepted.resolutionNote());

        JsonNode firstReviewedRead = publicRead(firstToken, 200);
        JsonNode secondReviewedRead = publicRead(secondToken, 200);
        assertEquals("ACCEPTED", discrepancyStatus(firstReviewedRead));
        assertEquals("ACCEPTED", discrepancyStatus(secondReviewedRead));
        assertEquals("REVIEW_REQUIRED", firstReviewedRead.get("reconciliationStatus").asString(),
                "The original reconciliation result must remain unchanged after a review decision.");
        assertFalse(firstReviewedRead.toString().contains(PRIVATE_REVIEW_NOTE));
        assertFalse(firstReviewedRead.toString().contains("resolutionReason"));
        assertFalse(firstReviewedRead.toString().contains("resolutionNote"));

        revokeShareLinkService.revoke(fixture.analysis().id(), fixture.analysis().owner(), firstLink.id());
        assertEquals(PUBLIC_NOT_FOUND, publicReadBody(firstToken, 404));
        JsonNode secondAfterFirstRevocation = publicRead(secondToken, 200);
        assertEquals("ACCEPTED", discrepancyStatus(secondAfterFirstRevocation),
                "Revoking one link must not affect the other link or its current projection.");
        assertEquals(1, shareLinkRepository.countActiveByAnalysisId(fixture.analysis().id(), clock.instant()));

        clock.advance(Duration.ofDays(8));
        assertEquals(PUBLIC_NOT_FOUND, publicReadBody(secondToken, 404),
                "The remaining link must become unavailable once the fixed clock passes its expiry.");
        assertEquals(0, shareLinkRepository.countActiveByAnalysisId(fixture.analysis().id(), clock.instant()));

        Analysis persistedAnalysis = analysisRepository.findOwnedById(
                fixture.analysis().id(), fixture.analysis().owner(), clock.instant()).orElseThrow();
        AnalysisJob persistedJob = analysisJobRepository.findByAnalysisId(fixture.analysis().id()).orElseThrow();
        GeneratedReport persistedReport = generatedReportRepository.findByAnalysisId(fixture.analysis().id())
                .orElseThrow();
        assertEquals(fixture.analysis().status(), persistedAnalysis.status());
        assertEquals(fixture.analysis().reconciliationStatus(), persistedAnalysis.reconciliationStatus());
        assertEquals(fixture.analysis().reviewStatus(), persistedAnalysis.reviewStatus());
        assertEquals(fixture.analysis().version(), persistedAnalysis.version());
        assertEquals(fixture.analysis().updatedAt(), persistedAnalysis.updatedAt());
        assertEquals(0, fixture.analysis().referenceTotal().compareTo(persistedAnalysis.referenceTotal()));
        assertEquals(0, fixture.analysis().invoicedTotal().compareTo(persistedAnalysis.invoicedTotal()));
        assertEquals(originalJob.status(), persistedJob.status());
        assertEquals(originalJob.currentStage(), persistedJob.currentStage());
        assertEquals(originalJob.attemptCount(), persistedJob.attemptCount());
        assertEquals(originalJob.retryable(), persistedJob.retryable());
        assertEquals(originalJob.updatedAt(), persistedJob.updatedAt());
        assertEquals(originalReport.id(), persistedReport.id());
        assertEquals(originalReport.analysisId(), persistedReport.analysisId());
        assertEquals(originalReport.generatedAt(), persistedReport.generatedAt());
        assertEquals(1, lineItemMatchRepository.countByAnalysisId(fixture.analysis().id()));
        assertEquals(1, reconciliationLineRepository.countByAnalysisId(fixture.analysis().id()));
        assertEquals(1, discrepancyRepository.countByAnalysisId(fixture.analysis().id()));

        verify(getReconciliationService, times(5)).get(fixture.analysis().id(), fixture.analysis().owner());
        verify(processingWorker, never()).process(fixture.analysis().id());
        verify(reconcileAnalysisService, never()).reconcile(fixture.analysis().id(), fixture.analysis().owner());
        verify(generateReportService, never()).generate(fixture.analysis().id(), fixture.analysis().owner());
        verify(detectDocumentTypesService, never()).detect(fixture.analysis().id(), fixture.analysis().owner());
        verify(runDocumentExtractionService, never()).run(fixture.analysis().id(), fixture.analysis().owner());
        verify(runLineItemMatchingService, never()).run(fixture.analysis().id(), fixture.analysis().owner());
    }

    private AnalysisFixture createCompletedAnalysisWithPersistedReconciliation() {
        UUID userId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertUser);
        userIds.add(userId);
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        Instant now = clock.instant();
        Instant createdAt = now.minusSeconds(600);
        Instant completedAt = now.minusSeconds(60);
        Analysis analysis = new Analysis(
                UUID.randomUUID(), owner, AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, null,
                AnalysisReconciliationStatus.REVIEW_REQUIRED, "Share workflow supplier", "share workflow supplier",
                "QUOTE", "REF-SHARE-WORKFLOW", "INV-SHARE-WORKFLOW", "USD",
                new BigDecimal("10.0000"), new BigDecimal("12.0000"), new BigDecimal("2.0000"),
                PriceTolerance.exactMatch(), false, null, null, 0, completedAt, null, createdAt, completedAt);
        analysis = analysisRepository.create(analysis);
        analysisIds.add(analysis.id());

        AnalysisJob job = new AnalysisJob(
                UUID.randomUUID(), analysis.id(), AnalysisJobStatus.COMPLETED, AnalysisStatus.COMPLETED,
                1, false, null, null, completedAt.minusSeconds(5), completedAt, createdAt, completedAt);
        analysisJobRepository.create(job);

        Document reference = createDocument(analysis, DocumentRole.REFERENCE, DocumentType.QUOTE, createdAt.plusSeconds(5));
        Document invoice = createDocument(analysis, DocumentRole.INVOICE, DocumentType.INVOICE, createdAt.plusSeconds(5));
        UUID referenceLineItemId = createConfirmedExtraction(reference, "Workflow reference item", createdAt.plusSeconds(10));
        UUID invoiceLineItemId = createConfirmedExtraction(invoice, "Workflow invoice item", createdAt.plusSeconds(10));

        Instant reconciliationAt = createdAt.plusSeconds(20);
        LineItemMatch match = lineItemMatchRepository.create(new LineItemMatch(
                UUID.randomUUID(), analysis.id(), referenceLineItemId, invoiceLineItemId,
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE, 0, null,
                reconciliationAt, reconciliationAt));
        ReconciliationLine line = new ReconciliationLine(
                UUID.randomUUID(), analysis.id(), match.id(), ReconciliationLineStatus.DIFFERENT,
                "ITEM-1", "ITEM-1", "Workflow widget", "Workflow widget",
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("10.0000"), new BigDecimal("12.0000"),
                new BigDecimal("10.0000"), new BigDecimal("12.0000"), BigDecimal.ZERO,
                new BigDecimal("2.0000"), new BigDecimal("2.0000"), reconciliationAt);
        ReconciliationLine persistedLine = reconciliationLineRepository.createAll(List.of(line)).getFirst();
        Discrepancy discrepancy = discrepancyRepository.createAll(List.of(new Discrepancy(
                UUID.randomUUID(), analysis.id(), persistedLine.id(), DiscrepancyType.UNIT_PRICE_MISMATCH,
                "10.0000", "12.0000", new BigDecimal("2.0000"), new BigDecimal("20.000000"),
                "The invoiced unit price differs from the reference price beyond the configured tolerance.",
                DiscrepancyResolutionStatus.OPEN, null, null, 0, null, reconciliationAt, reconciliationAt)))
                .getFirst();

        GeneratedReport report = new GeneratedReport(
                UUID.randomUUID(), analysis.id(), PRIVATE_STORAGE_KEY + analysis.id(), "application/pdf",
                128, REPORT_HASH, null, completedAt);
        generatedReportRepository.create(report);
        return new AnalysisFixture(analysis, discrepancy.id());
    }

    private Document createDocument(Analysis analysis, DocumentRole role, DocumentType type, Instant createdAt) {
        return documentRepository.create(Document.createUploaded(
                        UUID.randomUUID(), analysis.id(), role, role.name().toLowerCase() + ".pdf",
                        "application/pdf", 1024, 1, "a".repeat(64),
                        "share-revisit-document/" + UUID.randomUUID(), analysis.expiresAt(), createdAt)
                .withDetectedType(type)
                .withConfirmedType(type));
    }

    private UUID createConfirmedExtraction(Document document, String sourceText, Instant extractedAt) {
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Share workflow supplier", document.role() == DocumentRole.REFERENCE
                        ? "REF-SHARE-WORKFLOW" : "INV-SHARE-WORKFLOW",
                null, "USD", new BigDecimal("10.0000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10.0000"), List.of(new ExtractedLineItem(
                        0, "ITEM-1", sourceText, BigDecimal.ONE, "each", BigDecimal.TEN,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, 1, null, null)));
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "share-revisit-extractor-v1", "synthetic-test-model", 1, extractedAt);
        Instant confirmedAt = extractedAt.plusSeconds(1);
        PersistedExtraction confirmed = extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return confirmed.lineItemIds().getFirst();
    }

    private JsonNode publicRead(String token, int expectedStatus) throws Exception {
        return objectMapper.readTree(publicReadBody(token, expectedStatus));
    }

    private String publicReadBody(String token, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/public/reports/{token}", token))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result.getResponse().getContentAsString();
    }

    private static String rawToken(CreatedShareLink link) {
        String path = URI.create(link.publicUrl()).getRawPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String discrepancyStatus(JsonNode report) {
        assertEquals(1, report.get("discrepancies").size());
        return report.get("discrepancies").get(0).get("resolutionStatus").asString();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableTestClock() {
            return new MutableClock(BASE_NOW, ZoneOffset.UTC);
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        MutableClock(Instant initial, ZoneId zone) {
            this(new AtomicReference<>(initial), zone);
        }

        private MutableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(current, requestedZone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    private record AnalysisFixture(Analysis analysis, UUID discrepancyId) {
    }
}
