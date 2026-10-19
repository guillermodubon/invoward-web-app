package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.processing.application.service.RecoverProcessingJobTransaction;
import io.github.guillermodubon.invoward.processing.application.port.ProcessingWakePort;
import io.github.guillermodubon.invoward.processing.infrastructure.ProcessingWorker;
import io.github.guillermodubon.invoward.support.ai.FakeDocumentIntelligence;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeDocumentStorage;
import io.github.guillermodubon.invoward.support.storage.FakeReportStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.model.chat=none",
                "spring.ai.google.genai.chat.model=integration-test-model",
                "invoward.email.provider=disabled",
                "invoward.guest.session-ttl=24h"
        })
@Import(ExtractionGuestOwnershipIntegrationIT.TestProviderConfiguration.class)
class ExtractionGuestOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String ANALYSIS_NOT_FOUND =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String START_EXTRACTION_BODY =
            "{\"referenceType\":\"QUOTE\",\"invoiceType\":\"INVOICE\"}";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FakeDocumentStorage storage;
    @Autowired private FakeDocumentIntelligence intelligence;
    @Autowired private FakeReportStorage reportStorage;
    @Autowired private ProcessingWorker processingWorker;
    @Autowired private RecoverProcessingJobTransaction recoveryTransaction;
    @Autowired private AnalysisRepository analysisRepository;
    @Autowired private AnalysisJobRepository analysisJobRepository;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearTestDoubles() {
        storage.clear();
        intelligence.reset();
        reportStorage.clear();
    }

    @Test
    void guestOwnerCompletesPipelineWhileForeignAndInvalidCookiesRemainHiddenWithoutSlidingExpiry()
            throws Exception {
        URI baseUri = applicationUri();
        GuestAnalysis guestA = createGuestAnalysis(baseUri, csrfTicket(baseUri, null), null);
        long sessionsAfterGuestA = guestSessionCount();
        GuestRow guestABaseline = guestRow(guestA.guestSessionId());
        assertEquals(guestA.fixedExpiry(), guestABaseline.expiresAt());
        assertEquals(guestA.fixedExpiry(), analysisExpiry(guestA.analysisId()));
        assertFalse(guestA.analysisResponseBody().contains(guestA.guestSessionId().toString()));

        UUID referenceDocumentId = uploadDocument(
                baseUri, guestA, "REFERENCE", validPdf("guest-a-reference"), Instant.now().minusSeconds(30), 1);
        UUID invoiceDocumentId = uploadDocument(
                baseUri, guestA, "INVOICE", validPdf("guest-a-invoice"), Instant.now().minusSeconds(30), 2);
        assertEquals(2, storage.operationCalls(FakeDocumentStorage.Operation.STORE));

        String detectTypesPath = analysisPath(guestA.analysisId()) + "/documents/detect-types";
        Instant staleActivity = Instant.now().minusSeconds(30);
        markGuestActivityStale(guestA, staleActivity);
        HttpResponse<String> classificationRequested = postWithoutBody(
                baseUri, detectTypesPath, guestA.csrf(), guestA.guestCookie());
        assertEquals(202, classificationRequested.statusCode(), classificationRequested.body());
        assertEquals("/api/analyses/" + guestA.analysisId() + "/status",
                classificationRequested.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", classificationRequested.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode queuedClassification = objectMapper.readTree(classificationRequested.body());
        assertEquals("CLASSIFYING", queuedClassification.get("status").asString());
        assertEquals("QUEUED", queuedClassification.get("jobStatus").asString());
        assertEquals(0, intelligence.totalClassifyCalls(), "the HTTP command must not invoke AI");
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, staleActivity, 2);

        HttpResponse<String> repeatedClassificationRequest = postWithoutBody(
                baseUri, detectTypesPath, guestA.csrf(), guestA.guestCookie());
        assertEquals(202, repeatedClassificationRequest.statusCode(), repeatedClassificationRequest.body());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.analysis_jobs
                WHERE analysis_id = ? AND status = 'QUEUED' AND current_stage = 'CLASSIFYING'
                  AND attempt_count = 0
                """, Integer.class, guestA.analysisId()), "repeated request must not enqueue another attempt");
        assertEquals(0, intelligence.totalClassifyCalls(), "repeat command must still not invoke AI");

        intelligence.setClassification(referenceDocumentId, DocumentType.QUOTE);
        intelligence.setClassification(invoiceDocumentId, DocumentType.INVOICE);
        intelligence.setExtraction(extractionDraft());
        processWithoutGuestActivity(guestA);
        assertEquals(2, intelligence.totalClassifyCalls());

        HttpResponse<String> alreadyDetected = postWithoutBody(
                baseUri, detectTypesPath, guestA.csrf(), guestA.guestCookie());
        assertEquals(200, alreadyDetected.statusCode(), alreadyDetected.body());
        assertTrue(alreadyDetected.headers().firstValue("Location").isEmpty());
        assertEquals("CLASSIFYING", objectMapper.readTree(alreadyDetected.body()).get("status").asString());
        assertEquals(2, intelligence.totalClassifyCalls(), "completed detection must not be repeated");

        markGuestActivityStale(guestA, Instant.now().minusSeconds(30));
        HttpResponse<String> started = postJson(
                baseUri,
                analysisPath(guestA.analysisId()) + "/extract",
                guestA.csrf(),
                guestA.guestCookie(),
                START_EXTRACTION_BODY);
        assertEquals(202, started.statusCode(), started.body());
        assertEquals("/api/analyses/" + guestA.analysisId() + "/status",
                started.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", started.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode queued = objectMapper.readTree(started.body());
        assertEquals("EXTRACTING", queued.get("status").asString());
        assertEquals("QUEUED", queued.get("jobStatus").asString());
        assertEquals("EXTRACTING", queued.get("currentStage").asString());
        assertFalse(started.body().contains(guestA.guestSessionId().toString()));
        assertEquals(0, intelligence.extractCalls(), "the HTTP request must not call extraction AI");
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, Instant.now().minusSeconds(30), 2);

        processWithoutGuestActivity(guestA);
        assertEquals(2, intelligence.extractCalls());

        String extractionPath = analysisPath(guestA.analysisId()) + "/extraction";
        HttpResponse<String> initialReviewResponse = get(
                baseUri, extractionPath, guestA.csrf(), guestA.guestCookie());
        assertEquals(200, initialReviewResponse.statusCode());
        JsonNode initialReview = objectMapper.readTree(initialReviewResponse.body());
        assertEquals("DRAFT", initialReview.get("reference").get("status").asString());
        assertEquals("DRAFT", initialReview.get("invoice").get("status").asString());
        assertEquals(1, initialReview.get("reference").get("lines").size());
        assertEquals(1, initialReview.get("invoice").get("lines").size());

        markGuestActivityStale(guestA, Instant.now().minusSeconds(30));
        HttpResponse<String> read = get(baseUri, extractionPath, guestA.csrf(), guestA.guestCookie());
        assertEquals(200, read.statusCode());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, Instant.now().minusSeconds(30), 2);

        markGuestActivityStale(guestA, Instant.now().minusSeconds(30));
        HttpResponse<String> updated = putJson(
                baseUri,
                extractionPath,
                guestA.csrf(),
                guestA.guestCookie(),
                updatedReviewRequest(initialReview));
        assertEquals(200, updated.statusCode(), updated.body());
        JsonNode updatedReview = objectMapper.readTree(updated.body());
        assertEquals("Guest-corrected supplier", updatedReview.get("reference").get("vendorName").asString());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, Instant.now().minusSeconds(30), 2);

        markGuestActivityStale(guestA, Instant.now().minusSeconds(30));
        HttpResponse<String> confirmed = postJson(
                baseUri,
                extractionPath + "/confirm",
                guestA.csrf(),
                guestA.guestCookie(),
                confirmationRequest(updatedReview));
        assertEquals(202, confirmed.statusCode(), confirmed.body());
        assertEquals("/api/analyses/" + guestA.analysisId() + "/status",
                confirmed.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", confirmed.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode queuedMatching = objectMapper.readTree(confirmed.body());
        assertEquals("MATCHING", queuedMatching.get("status").asString());
        assertEquals("QUEUED", queuedMatching.get("jobStatus").asString());
        assertEquals("MATCHING", queuedMatching.get("currentStage").asString());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, Instant.now().minusSeconds(30), 2);

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.analysis_jobs "
                        + "WHERE analysis_id = ? AND status = 'QUEUED' AND current_stage = 'MATCHING'",
                Integer.class,
                guestA.analysisId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.line_item_matches WHERE analysis_id = ?",
                Integer.class,
                guestA.analysisId()), "matching must not run in the confirmation request");

        markGuestActivityStale(guestA, Instant.now().minusSeconds(30));
        HttpResponse<String> confirmedReviewResponse = get(
                baseUri, extractionPath, guestA.csrf(), guestA.guestCookie());
        assertEquals(200, confirmedReviewResponse.statusCode());
        JsonNode confirmedReview = objectMapper.readTree(confirmedReviewResponse.body());
        assertEquals("CONFIRMED", confirmedReview.get("reference").get("status").asString());
        assertEquals("CONFIRMED", confirmedReview.get("invoice").get("status").asString());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, Instant.now().minusSeconds(30), 2);

        processWithoutGuestActivity(guestA);
        String analysis = analysisPath(guestA.analysisId());
        HttpResponse<String> matchesResponse = get(
                baseUri, analysis + "/matches", guestA.csrf(), guestA.guestCookie());
        assertEquals(200, matchesResponse.statusCode(), matchesResponse.body());
        JsonNode matchSet = objectMapper.readTree(matchesResponse.body());
        assertTrue(matchSet.get("reviewRequired").asBoolean());
        assertTrue(matchSet.get("matches").valueStream()
                .anyMatch(match -> "UNMATCHED_REFERENCE".equals(match.get("status").asString())));
        assertTrue(matchSet.get("matches").valueStream()
                .anyMatch(match -> "UNMATCHED_INVOICE".equals(match.get("status").asString())));

        Instant staleBeforeMatchConfirmation = Instant.now().minusSeconds(30);
        markGuestActivityStale(guestA, staleBeforeMatchConfirmation);
        HttpResponse<String> confirmedMatches = postWithoutBody(
                baseUri, analysis + "/matches/confirm", guestA.csrf(), guestA.guestCookie());
        assertEquals(202, confirmedMatches.statusCode(), confirmedMatches.body());
        assertEquals("RECONCILING", objectMapper.readTree(confirmedMatches.body()).get("status").asString());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, staleBeforeMatchConfirmation, 2);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.reconciliation_lines WHERE analysis_id = ?",
                Integer.class,
                guestA.analysisId()), "reconciliation must not run in the match-confirmation request");

        Instant staleBeforeReconcileRequest = Instant.now().minusSeconds(30);
        markGuestActivityStale(guestA, staleBeforeReconcileRequest);
        HttpResponse<String> reconciliationRequested = postWithoutBody(
                baseUri, analysis + "/reconcile", guestA.csrf(), guestA.guestCookie());
        assertEquals(202, reconciliationRequested.statusCode(), reconciliationRequested.body());
        assertEquals("RECONCILING", objectMapper.readTree(reconciliationRequested.body()).get("status").asString());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, staleBeforeReconcileRequest, 2);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.reconciliation_lines WHERE analysis_id = ?",
                Integer.class,
                guestA.analysisId()), "the HTTP command must only queue reconciliation");
        processWithoutGuestActivity(guestA);

        HttpResponse<String> reconciliationResponse = get(
                baseUri, analysis + "/reconciliation", guestA.csrf(), guestA.guestCookie());
        assertEquals(200, reconciliationResponse.statusCode(), reconciliationResponse.body());
        JsonNode reconciliation = objectMapper.readTree(reconciliationResponse.body());
        assertEquals(2, reconciliation.get("summary").get("lineCount").asInt());
        assertEquals(1, reconciliation.get("summary").get("missingCount").asInt());
        assertEquals(1, reconciliation.get("summary").get("unexpectedCount").asInt());

        Instant staleBeforeReportRequest = Instant.now().minusSeconds(30);
        markGuestActivityStale(guestA, staleBeforeReportRequest);
        HttpResponse<String> reportRequested = postWithoutBody(
                baseUri, analysis + "/report", guestA.csrf(), guestA.guestCookie());
        assertEquals(202, reportRequested.statusCode(), reportRequested.body());
        assertEquals("GENERATING_REPORT", objectMapper.readTree(reportRequested.body()).get("status").asString());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, staleBeforeReportRequest, 2);
        assertEquals(0, reportStorage.operationCalls(FakeReportStorage.Operation.STORE),
                "the report HTTP command must not render or store the report");
        processWithoutGuestActivity(guestA);

        HttpResponse<String> completedStatus = get(
                baseUri, analysis + "/status", guestA.csrf(), guestA.guestCookie());
        assertEquals(200, completedStatus.statusCode());
        assertEquals("COMPLETED", objectMapper.readTree(completedStatus.body()).get("status").asString());
        HttpResponse<String> reportResponse = get(
                baseUri, analysis + "/report", guestA.csrf(), guestA.guestCookie());
        assertEquals(200, reportResponse.statusCode(), reportResponse.body());
        assertEquals("application/pdf", objectMapper.readTree(reportResponse.body())
                .get("contentType").asString());
        assertFalse(reportResponse.body().contains("storageKey"));
        HttpResponse<String> csvResponse = get(
                baseUri, analysis + "/export.csv", guestA.csrf(), guestA.guestCookie());
        assertEquals(200, csvResponse.statusCode(), csvResponse.body());
        assertTrue(csvResponse.headers().firstValue("Content-Type").orElseThrow()
                .startsWith("text/csv;charset=UTF-8"));
        assertTrue(csvResponse.body().contains("MISSING_ITEM"));
        assertTrue(csvResponse.body().contains("UNEXPECTED_ITEM"));
        assertEquals(1, reportStorage.storedObjectCount());
        assertEquals(guestA.fixedExpiry(), guestRow(guestA.guestSessionId()).expiresAt());
        assertEquals(guestA.fixedExpiry(), analysisExpiry(guestA.analysisId()));
        assertEquals(sessionsAfterGuestA, guestSessionCount(),
                "nested processing routes must not create or rotate a guest session");

        GuestAnalysis pendingAnalysis = createGuestAnalysis(
                baseUri, guestA.csrf(), guestA.guestCookie());
        assertEquals(guestA.guestSessionId(), pendingAnalysis.guestSessionId(),
                "a valid guest cookie should reuse its existing fixed-lifetime session");
        assertEquals(sessionsAfterGuestA, guestSessionCount(),
                "creating another Analysis with the active guest cookie must not create a session");
        uploadDocument(
                baseUri, pendingAnalysis, "REFERENCE", validPdf("pending-reference"),
                Instant.now().minusSeconds(30), 1);
        UUID pendingInvoiceId = uploadDocument(
                baseUri, pendingAnalysis, "INVOICE", validPdf("pending-invoice"),
                Instant.now().minusSeconds(30), 2);
        assertNotNull(pendingInvoiceId);

        GuestAnalysis guestB = createGuestAnalysis(baseUri, csrfTicket(baseUri, null), null);
        ExpiredProcessingGuest expiredGuest = createExpiredProcessingGuest(AnalysisJobStatus.QUEUED);
        GuestRow expiredBeforeProcessing = guestRow(expiredGuest.guestSessionId());
        EnumMap<FakeDocumentStorage.Operation, Integer> storageCallsBeforeExpiredProcessing = storageCallCounts();
        EnumMap<FakeReportStorage.Operation, Integer> reportCallsBeforeExpiredProcessing = reportCallCounts();
        int classificationsBeforeExpiredProcessing = intelligence.totalClassifyCalls();
        int extractionsBeforeExpiredProcessing = intelligence.extractCalls();
        processingWorker.process(expiredGuest.analysisId());
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM invoward.analyses WHERE id = ?",
                String.class, expiredGuest.analysisId()));
        assertEquals("ANALYSIS_EXPIRED", jdbcTemplate.queryForObject(
                "SELECT failure_code FROM invoward.analyses WHERE id = ?",
                String.class, expiredGuest.analysisId()));
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM invoward.analysis_jobs WHERE analysis_id = ?",
                String.class, expiredGuest.analysisId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM invoward.analysis_jobs WHERE analysis_id = ?",
                Integer.class, expiredGuest.analysisId()),
                "expired queued work must fail before an attempt starts");
        assertEquals(storageCallsBeforeExpiredProcessing, storageCallCounts(),
                "expired guest work must not access document storage");
        assertEquals(reportCallsBeforeExpiredProcessing, reportCallCounts(),
                "expired guest work must not access report storage");
        assertEquals(classificationsBeforeExpiredProcessing, intelligence.totalClassifyCalls(),
                "expired guest work must not call classification AI");
        assertEquals(extractionsBeforeExpiredProcessing, intelligence.extractCalls(),
                "expired guest work must not call extraction AI");
        assertEquals(expiredBeforeProcessing, guestRow(expiredGuest.guestSessionId()),
                "background expiry handling must not touch guest activity");
        assertEquals(expiredGuest.expiresAt(), analysisExpiry(expiredGuest.analysisId()));
        UUID expiredGuestId = expiredGuest.guestSessionId();
        String unknownGuestCookie = UUID.randomUUID().toString();
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.guest_sessions WHERE id = ?",
                Integer.class,
                UUID.fromString(unknownGuestCookie)));

        GuestRow guestABeforeRejectedRequests = guestRow(guestA.guestSessionId());
        GuestRow guestBBeforeRejectedRequests = guestRow(guestB.guestSessionId());
        GuestRow expiredBeforeRejectedRequests = guestRow(expiredGuestId);
        long guestSessionsBeforeRejectedRequests = guestSessionCount();
        EnumMap<FakeDocumentStorage.Operation, Integer> storageCallsBeforeRejectedRequests = storageCallCounts();
        EnumMap<FakeReportStorage.Operation, Integer> reportCallsBeforeRejectedRequests = reportCallCounts();
        int classificationCallsBeforeRejectedRequests = intelligence.totalClassifyCalls();
        int extractionCallsBeforeRejectedRequests = intelligence.extractCalls();

        List<GuestAccess> invalidGuestAccesses = List.of(
                new GuestAccess("foreign guest", guestB.csrf(), guestB.guestCookie()),
                new GuestAccess("missing guest cookie", guestA.csrf(), null),
                new GuestAccess("malformed guest cookie", guestA.csrf(), "not-a-uuid"),
                new GuestAccess("unknown guest cookie", guestA.csrf(), unknownGuestCookie),
                new GuestAccess("expired guest cookie", guestA.csrf(), expiredGuestId.toString()));

        for (GuestAccess access : invalidGuestAccesses) {
            assertProcessingRoutesNotFound(
                    baseUri, guestA.analysisId(), referenceDocumentId, updatedReview, reconciliation, access);
            assertExtractionRoutesNotFound(baseUri, guestA.analysisId(), updatedReview, access);
            assertExtractionRoutesNotFound(baseUri, pendingAnalysis.analysisId(), updatedReview, access);
        }
        assertNotFound(get(baseUri, analysisPath(expiredGuest.analysisId()) + "/status",
                guestA.csrf(), expiredGuestId.toString()), "expired guest owner");
        assertNotFound(postWithoutBody(baseUri, analysisPath(expiredGuest.analysisId()) + "/retry",
                guestA.csrf(), expiredGuestId.toString()), "expired guest owner");

        assertEquals(guestSessionsBeforeRejectedRequests, guestSessionCount(),
                "nested extraction routes must never create a GuestSession");
        assertEquals(guestABeforeRejectedRequests, guestRow(guestA.guestSessionId()),
                "rejected requests must not touch the owner guest session or slide expiry");
        assertEquals(guestBBeforeRejectedRequests, guestRow(guestB.guestSessionId()),
                "a foreign guest session must not be touched by rejected requests");
        assertEquals(expiredBeforeRejectedRequests, guestRow(expiredGuestId));
        assertEquals(guestA.fixedExpiry(), analysisExpiry(guestA.analysisId()));
        assertEquals(pendingAnalysis.fixedExpiry(), analysisExpiry(pendingAnalysis.analysisId()));
        assertEquals(storageCallsBeforeRejectedRequests, storageCallCounts(),
                "failed ownership must be resolved before storage access");
        assertEquals(classificationCallsBeforeRejectedRequests, intelligence.totalClassifyCalls(),
                "failed ownership must be resolved before classification");
        assertEquals(extractionCallsBeforeRejectedRequests, intelligence.extractCalls(),
                "failed ownership must be resolved before extraction");
        assertEquals(reportCallsBeforeRejectedRequests, reportCallCounts(),
                "failed ownership must be resolved before report storage access");
    }

    @Test
    void expiredRunningGuestRecoveryDoesNotRunExternalWorkOrTouchGuestActivity() throws Exception {
        ExpiredProcessingGuest guest = createExpiredProcessingGuest(AnalysisJobStatus.RUNNING);
        GuestRow beforeRecovery = guestRow(guest.guestSessionId());
        EnumMap<FakeDocumentStorage.Operation, Integer> storageBeforeRecovery = storageCallCounts();
        EnumMap<FakeReportStorage.Operation, Integer> reportsBeforeRecovery = reportCallCounts();
        int classificationsBeforeRecovery = intelligence.totalClassifyCalls();
        int extractionsBeforeRecovery = intelligence.extractCalls();

        recoveryTransaction.recover(guest.analysisId());

        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM invoward.analyses WHERE id = ?", String.class, guest.analysisId()));
        assertEquals("ANALYSIS_EXPIRED", jdbcTemplate.queryForObject(
                "SELECT failure_code FROM invoward.analyses WHERE id = ?", String.class, guest.analysisId()));
        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM invoward.analysis_jobs WHERE analysis_id = ?", String.class, guest.analysisId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM invoward.analysis_jobs WHERE analysis_id = ?",
                Integer.class, guest.analysisId()));
        assertEquals(storageBeforeRecovery, storageCallCounts());
        assertEquals(reportsBeforeRecovery, reportCallCounts());
        assertEquals(classificationsBeforeRecovery, intelligence.totalClassifyCalls());
        assertEquals(extractionsBeforeRecovery, intelligence.extractCalls());
        assertEquals(beforeRecovery, guestRow(guest.guestSessionId()),
                "startup recovery must not touch guest activity");
        assertEquals(guest.expiresAt(), analysisExpiry(guest.analysisId()));

        CsrfTicket csrf = csrfTicket(applicationUri(), null);
        assertNotFound(get(applicationUri(), analysisPath(guest.analysisId()) + "/status",
                csrf, guest.guestSessionId().toString()), "expired running guest");
    }

    @Test
    void activeCacheIsSharedAcrossAnalysesWithoutSharingGuestReviewCorrections() throws Exception {
        URI baseUri = applicationUri();
        byte[] referencePdf = validPdf("shared-reference-cache-content");
        byte[] invoicePdf = validPdf("shared-invoice-cache-content");
        GuestAnalysis firstAnalysis = createGuestAnalysis(baseUri, csrfTicket(baseUri, null), null);

        UUID firstReferenceId = uploadDocument(
                baseUri, firstAnalysis, "REFERENCE", referencePdf, Instant.now().minusSeconds(30), 1);
        UUID firstInvoiceId = uploadDocument(
                baseUri, firstAnalysis, "INVOICE", invoicePdf, Instant.now().minusSeconds(30), 2);
        intelligence.setClassification(firstReferenceId, DocumentType.QUOTE);
        intelligence.setClassification(firstInvoiceId, DocumentType.INVOICE);
        intelligence.setExtraction(extractionDraft());
        processingWorker.process(firstAnalysis.analysisId());

        String firstExtractionPath = analysisPath(firstAnalysis.analysisId()) + "/extraction";
        int extractionCallsBeforeFirstRequest = intelligence.extractCalls();
        HttpResponse<String> firstStarted = postJson(
                baseUri, analysisPath(firstAnalysis.analysisId()) + "/extract",
                firstAnalysis.csrf(), firstAnalysis.guestCookie(), START_EXTRACTION_BODY);
        assertEquals(202, firstStarted.statusCode(), firstStarted.body());
        assertEquals("/api/analyses/" + firstAnalysis.analysisId() + "/status",
                firstStarted.headers().firstValue("Location").orElseThrow());
        assertEquals(extractionCallsBeforeFirstRequest, intelligence.extractCalls(),
                "provider work must not run on the request thread");
        processingWorker.process(firstAnalysis.analysisId());
        JsonNode firstDraft = objectMapper.readTree(get(
                baseUri, firstExtractionPath, firstAnalysis.csrf(), firstAnalysis.guestCookie()).body());
        assertEquals(2, intelligence.extractCalls(), "the two uncached document contents should call the provider");
        assertEquals("Acme Supplies", firstDraft.get("reference").get("vendorName").asString());

        assertEquals(200, get(baseUri, firstExtractionPath, firstAnalysis.csrf(), firstAnalysis.guestCookie())
                .statusCode());
        HttpResponse<String> firstUpdated = putJson(
                baseUri, firstExtractionPath, firstAnalysis.csrf(), firstAnalysis.guestCookie(),
                updatedReviewRequest(firstDraft));
        assertEquals(200, firstUpdated.statusCode(), firstUpdated.body());
        JsonNode firstCorrectedReview = objectMapper.readTree(firstUpdated.body());
        assertEquals("Guest-corrected supplier",
                firstCorrectedReview.get("reference").get("vendorName").asString());

        HttpResponse<String> firstConfirmed = postJson(
                baseUri, firstExtractionPath + "/confirm", firstAnalysis.csrf(), firstAnalysis.guestCookie(),
                confirmationRequest(firstCorrectedReview));
        assertEquals(202, firstConfirmed.statusCode(), firstConfirmed.body());
        assertEquals("/api/analyses/" + firstAnalysis.analysisId() + "/status",
                firstConfirmed.headers().firstValue("Location").orElseThrow());
        JsonNode firstQueuedMatching = objectMapper.readTree(firstConfirmed.body());
        assertEquals("MATCHING", firstQueuedMatching.get("status").asString());
        assertEquals("QUEUED", firstQueuedMatching.get("jobStatus").asString());
        assertEquals("MATCHING", firstQueuedMatching.get("currentStage").asString());
        JsonNode firstConfirmedReview = objectMapper.readTree(get(
                baseUri, firstExtractionPath, firstAnalysis.csrf(), firstAnalysis.guestCookie()).body());
        assertEquals("CONFIRMED", firstConfirmedReview.get("reference").get("status").asString());
        assertEquals("CONFIRMED", firstConfirmedReview.get("invoice").get("status").asString());
        assertEquals("Guest-corrected supplier",
                firstConfirmedReview.get("reference").get("vendorName").asString());

        GuestAnalysis secondAnalysis = createGuestAnalysis(baseUri, csrfTicket(baseUri, null), null);
        UUID secondReferenceId = uploadDocument(
                baseUri, secondAnalysis, "REFERENCE", referencePdf, Instant.now().minusSeconds(30), 1);
        UUID secondInvoiceId = uploadDocument(
                baseUri, secondAnalysis, "INVOICE", invoicePdf, Instant.now().minusSeconds(30), 2);
        assertEquals(documentSha(firstReferenceId), documentSha(secondReferenceId));
        assertEquals(documentSha(firstInvoiceId), documentSha(secondInvoiceId));
        intelligence.setClassification(secondReferenceId, DocumentType.QUOTE);
        intelligence.setClassification(secondInvoiceId, DocumentType.INVOICE);
        processingWorker.process(secondAnalysis.analysisId());

        int aiCallsBeforeCacheHit = intelligence.extractCalls();
        EnumMap<FakeDocumentStorage.Operation, Integer> storageCallsBeforeCacheHit = storageCallCounts();
        String secondExtractionPath = analysisPath(secondAnalysis.analysisId()) + "/extraction";
        HttpResponse<String> secondStarted = postJson(
                baseUri, analysisPath(secondAnalysis.analysisId()) + "/extract",
                secondAnalysis.csrf(), secondAnalysis.guestCookie(), START_EXTRACTION_BODY);
        assertEquals(202, secondStarted.statusCode(), secondStarted.body());
        assertEquals("/api/analyses/" + secondAnalysis.analysisId() + "/status",
                secondStarted.headers().firstValue("Location").orElseThrow());
        assertEquals(aiCallsBeforeCacheHit, intelligence.extractCalls(),
                "the request must return before any cache/provider extraction work");
        processingWorker.process(secondAnalysis.analysisId());
        JsonNode secondDraft = objectMapper.readTree(get(
                baseUri, secondExtractionPath, secondAnalysis.csrf(), secondAnalysis.guestCookie()).body());
        assertEquals(aiCallsBeforeCacheHit, intelligence.extractCalls(),
                "active cache entries must avoid repeated AI extraction");
        assertEquals(storageCallsBeforeCacheHit, storageCallCounts(),
                "cache hits must not materialize document files from private storage");
        assertEquals("DRAFT", secondDraft.get("reference").get("status").asString());
        assertEquals("Acme Supplies", secondDraft.get("reference").get("vendorName").asString(),
                "the cache must contain provider output, not another analysis's human correction");
        assertEquals("Fastener pack",
                secondDraft.get("reference").get("lines").get(0).get("description").asString());

        JsonNode secondReview = objectMapper.readTree(get(
                baseUri, secondExtractionPath, secondAnalysis.csrf(), secondAnalysis.guestCookie()).body());
        assertEquals("Acme Supplies", secondReview.get("reference").get("vendorName").asString());
        assertEquals("CONFIRMED", objectMapper.readTree(get(
                baseUri, firstExtractionPath, firstAnalysis.csrf(), firstAnalysis.guestCookie()).body())
                .get("reference").get("status").asString());
        assertEquals("Guest-corrected supplier", objectMapper.readTree(get(
                baseUri, firstExtractionPath, firstAnalysis.csrf(), firstAnalysis.guestCookie()).body())
                .get("reference").get("vendorName").asString());
    }

    private GuestAnalysis createGuestAnalysis(URI baseUri, CsrfTicket csrf, String existingGuestCookie)
            throws Exception {
        HttpResponse<String> response = postJson(baseUri, "/api/analyses", csrf, existingGuestCookie, "{}");
        assertEquals(202, response.statusCode(), response.body());
        UUID analysisId = UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
        String guestCookie = cookieValue(response, GUEST_COOKIE);
        UUID guestSessionId = UUID.fromString(guestCookie);
        GuestRow row = guestRow(guestSessionId);
        assertEquals(row.expiresAt(), analysisExpiry(analysisId));
        assertFalse(response.body().contains(guestSessionId.toString()),
                "the guest bearer id must not be exposed in the Analysis response");
        assertEquals("/api/analyses/" + analysisId,
                response.headers().firstValue("Location").orElseThrow());
        return new GuestAnalysis(analysisId, guestSessionId, guestCookie, row.expiresAt(), csrf, response.body());
    }

    private UUID uploadDocument(
            URI baseUri,
            GuestAnalysis guest,
            String role,
            byte[] pdf,
            Instant staleLastSeen,
            int expectedDocumentCount) throws Exception {
        String boundary = "InvoWardBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"role\"\r\n\r\n"
                + role + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"guest-"
                + role.toLowerCase() + ".pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(pdf);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));

        markGuestActivityStale(guest, staleLastSeen);
        HttpRequest request = HttpRequest.newBuilder(
                        baseUri.resolve(analysisPath(guest.analysisId()) + "/documents"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", cookies(guest.csrf().sessionCookie(), guest.guestCookie()))
                .header("X-CSRF-TOKEN", guest.csrf().token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertNoGuestCookieSet(response);
        assertEquals(201, response.statusCode(), response.body());
        JsonNode document = objectMapper.readTree(response.body());
        UUID documentId = UUID.fromString(document.get("id").asString());
        assertEquals(guest.fixedExpiry(), Instant.parse(document.get("expiresAt").asString()));
        assertEquals(expectedDocumentCount, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.documents
                WHERE analysis_id = ? AND expires_at = ?
                """, Integer.class, guest.analysisId(), Timestamp.from(guest.fixedExpiry())));
        assertSuccessfulActivityWithoutSlidingExpiry(guest, staleLastSeen, expectedDocumentCount);
        return documentId;
    }

    private HttpResponse<String> uploadDocumentResponse(
            URI baseUri,
            UUID analysisId,
            CsrfTicket csrf,
            String guestCookie,
            String role,
            byte[] pdf) throws Exception {
        String boundary = "InvoWardBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"role\"\r\n\r\n"
                + role + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"guest-"
                + role.toLowerCase() + ".pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(pdf);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(analysisPath(analysisId) + "/documents"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", cookies(csrf.sessionCookie(), guestCookie))
                .header("X-CSRF-TOKEN", csrf.token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertNoGuestCookieSet(response);
        return response;
    }

    private void assertExtractionRoutesNotFound(
            URI baseUri, UUID analysisId, JsonNode validReview, GuestAccess access) throws Exception {
        String analysis = analysisPath(analysisId);
        String extraction = analysis + "/extraction";
        assertNotFound(postWithoutBody(
                baseUri, analysis + "/documents/detect-types", access.csrf(), access.guestCookie()), access.label());
        assertNotFound(postJson(
                baseUri, analysis + "/extract", access.csrf(), access.guestCookie(), START_EXTRACTION_BODY),
                access.label());
        assertNotFound(get(baseUri, extraction, access.csrf(), access.guestCookie()), access.label());
        assertNotFound(putJson(
                baseUri, extraction, access.csrf(), access.guestCookie(), updatedReviewRequest(validReview)),
                access.label());
        assertNotFound(postJson(
                baseUri,
                extraction + "/confirm",
                access.csrf(),
                access.guestCookie(),
                confirmationRequest(validReview)),
                access.label());
    }

    private void assertProcessingRoutesNotFound(
            URI baseUri,
            UUID analysisId,
            UUID documentId,
            JsonNode validReview,
            JsonNode reconciliation,
            GuestAccess access) throws Exception {
        String analysis = analysisPath(analysisId);
        String extraction = analysis + "/extraction";
        String matches = analysis + "/matches";
        String reconciliationPath = analysis + "/reconciliation";
        String report = analysis + "/report";
        assertNotFound(get(baseUri, analysis, access.csrf(), access.guestCookie()), access.label());
        assertNotFound(get(baseUri, analysis + "/status", access.csrf(), access.guestCookie()), access.label());
        assertNotFound(get(baseUri, analysis + "/documents", access.csrf(), access.guestCookie()), access.label());
        assertNotFound(get(baseUri, analysis + "/documents/" + documentId,
                access.csrf(), access.guestCookie()), access.label());
        assertNotFound(uploadDocumentResponse(
                baseUri, analysisId, access.csrf(), access.guestCookie(), "REFERENCE", validPdf("foreign")),
                access.label());
        assertNotFound(postWithoutBody(
                baseUri, analysis + "/documents/detect-types", access.csrf(), access.guestCookie()), access.label());
        assertNotFound(postJson(
                baseUri, analysis + "/extract", access.csrf(), access.guestCookie(), START_EXTRACTION_BODY),
                access.label());
        assertNotFound(get(baseUri, extraction, access.csrf(), access.guestCookie()), access.label());
        assertNotFound(putJson(
                baseUri, extraction, access.csrf(), access.guestCookie(), updatedReviewRequest(validReview)),
                access.label());
        assertNotFound(postJson(
                baseUri, extraction + "/confirm", access.csrf(), access.guestCookie(),
                confirmationRequest(validReview)), access.label());
        assertNotFound(get(baseUri, matches, access.csrf(), access.guestCookie()), access.label());
        assertNotFound(patchJson(
                baseUri, matches + "/" + UUID.randomUUID(), access.csrf(), access.guestCookie(),
                "{\"expectedVersion\":0,\"action\":\"NO_MATCH\"}"), access.label());
        assertNotFound(postWithoutBody(
                baseUri, matches + "/confirm", access.csrf(), access.guestCookie()), access.label());
        assertNotFound(postWithoutBody(
                baseUri, analysis + "/reconcile", access.csrf(), access.guestCookie()), access.label());
        assertNotFound(get(baseUri, reconciliationPath, access.csrf(), access.guestCookie()), access.label());
        JsonNode discrepancy = reconciliation.get("discrepancies").get(0);
        String discrepancyId = discrepancy.get("id").asString();
        assertNotFound(patchJson(
                baseUri, analysis + "/discrepancies/" + discrepancyId, access.csrf(), access.guestCookie(),
                "{\"expectedVersion\":0,\"resolutionStatus\":\"ACCEPTED\","
                        + "\"resolutionReason\":\"APPROVED_CHANGE\",\"resolutionNote\":null}"), access.label());
        assertNotFound(get(baseUri, analysis + "/discrepancies/" + discrepancyId + "/evidence",
                access.csrf(), access.guestCookie()), access.label());
        assertNotFound(postWithoutBody(baseUri, report, access.csrf(), access.guestCookie()), access.label());
        assertNotFound(get(baseUri, report, access.csrf(), access.guestCookie()), access.label());
        assertNotFound(get(baseUri, analysis + "/export.csv", access.csrf(), access.guestCookie()), access.label());
        assertNotFound(postWithoutBody(
                baseUri, analysis + "/retry", access.csrf(), access.guestCookie()), access.label());
    }

    private HttpResponse<String> csrfTicketRequest(URI baseUri, String sessionCookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve("/api/auth/csrf"))
                .timeout(REQUEST_TIMEOUT);
        if (sessionCookie != null) {
            request.header("Cookie", SESSION_COOKIE + "=" + sessionCookie);
        }
        return httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private CsrfTicket csrfTicket(URI baseUri, String existingSessionCookie) throws Exception {
        HttpResponse<String> response = csrfTicketRequest(baseUri, existingSessionCookie);
        assertEquals(200, response.statusCode());
        JsonNode csrf = objectMapper.readTree(response.body());
        String sessionCookie = cookieValue(response, SESSION_COOKIE);
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        assertFalse(csrf.get("token").asString().isBlank());
        return new CsrfTicket(sessionCookie, csrf.get("token").asString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Cookie", cookies(csrf.sessionCookie(), guestCookie))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertNoGuestCookieSet(response);
        return response;
    }

    private HttpResponse<String> postWithoutBody(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Cookie", cookies(csrf.sessionCookie(), guestCookie))
                .header("X-CSRF-TOKEN", csrf.token())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertNoGuestCookieSet(response);
        return response;
    }

    private HttpResponse<String> postJson(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie, String body) throws Exception {
        HttpRequest request = jsonRequest(baseUri, path, csrf, guestCookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (!"/api/analyses".equals(path)) {
            assertNoGuestCookieSet(response);
        }
        return response;
    }

    private HttpResponse<String> putJson(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie, String body) throws Exception {
        HttpRequest request = jsonRequest(baseUri, path, csrf, guestCookie)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertNoGuestCookieSet(response);
        return response;
    }

    private HttpResponse<String> patchJson(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie, String body) throws Exception {
        HttpRequest request = jsonRequest(baseUri, path, csrf, guestCookie)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertNoGuestCookieSet(response);
        return response;
    }

    private HttpRequest.Builder jsonRequest(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Cookie", cookies(csrf.sessionCookie(), guestCookie))
                .header("X-CSRF-TOKEN", csrf.token());
    }

    private String updatedReviewRequest(JsonNode review) throws Exception {
        return objectMapper.writeValueAsString(Map.of("documents", List.of(
                updatedDocument(review.get("reference"), "QUOTE",
                        "Reference industrial fastener assembly", "GUEST-REF-101"),
                updatedDocument(review.get("invoice"), "INVOICE",
                        "Invoice cloud accounting subscription", "GUEST-INV-202"))));
    }

    private static Map<String, Object> updatedDocument(
            JsonNode document, String confirmedType, String description, String itemCode) {
        JsonNode line = document.get("lines").get(0);
        Map<String, Object> updatedLine = Map.of(
                "id", line.get("id").asString(),
                "itemCode", itemCode,
                "description", description,
                "quantity", line.get("quantity").decimalValue(),
                "unit", line.get("unit").asString(),
                "unitPrice", line.get("unitPrice").decimalValue(),
                "discountAmount", line.get("discountAmount").decimalValue(),
                "taxAmount", line.get("taxAmount").decimalValue(),
                "lineTotal", line.get("lineTotal").decimalValue());
        return Map.ofEntries(
                Map.entry("documentId", document.get("documentId").asString()),
                Map.entry("expectedVersion", document.get("version").asLong()),
                Map.entry("confirmedType", confirmedType),
                Map.entry("vendorName", "Guest-corrected supplier"),
                Map.entry("documentNumber", document.get("documentNumber").asString()),
                Map.entry("documentDate", document.get("documentDate").asString()),
                Map.entry("currency", document.get("currency").asString()),
                Map.entry("subtotal", document.get("subtotal").decimalValue()),
                Map.entry("discountTotal", document.get("discountTotal").decimalValue()),
                Map.entry("taxTotal", document.get("taxTotal").decimalValue()),
                Map.entry("total", document.get("total").decimalValue()),
                Map.entry("lines", List.of(updatedLine)));
    }

    private String confirmationRequest(JsonNode review) throws Exception {
        JsonNode reference = review.get("reference");
        JsonNode invoice = review.get("invoice");
        return objectMapper.writeValueAsString(Map.of(
                "referenceDocumentId", reference.get("documentId").asString(),
                "referenceExpectedVersion", reference.get("version").asLong(),
                "invoiceDocumentId", invoice.get("documentId").asString(),
                "invoiceExpectedVersion", invoice.get("version").asLong()));
    }

    private void assertSuccessfulActivityWithoutSlidingExpiry(
            GuestAnalysis guest, Instant oldActivity, int expectedDocumentCount) {
        GuestRow after = guestRow(guest.guestSessionId());
        Instant validStaleActivity = oldActivity.isBefore(after.createdAt()) ? after.createdAt() : oldActivity;
        assertTrue(after.lastSeenAt().isAfter(validStaleActivity),
                "a successful request should record guest activity after its previous last_seen_at");
        assertEquals(guest.fixedExpiry(), after.expiresAt());
        assertEquals(guest.fixedExpiry(), analysisExpiry(guest.analysisId()));
        assertEquals(expectedDocumentCount, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.documents
                WHERE analysis_id = ? AND expires_at = ?
                """, Integer.class, guest.analysisId(), Timestamp.from(guest.fixedExpiry())));
    }

    private void processWithoutGuestActivity(GuestAnalysis guest) {
        GuestRow before = guestRow(guest.guestSessionId());
        processingWorker.process(guest.analysisId());
        assertEquals(before, guestRow(guest.guestSessionId()),
                "background processing must not update guest activity or extend its fixed expiry");
        assertEquals(guest.fixedExpiry(), analysisExpiry(guest.analysisId()));
    }

    private void markGuestActivityStale(GuestAnalysis guest, Instant oldActivity) {
        Instant createdAt = guestRow(guest.guestSessionId()).createdAt();
        Instant validStaleActivity = oldActivity.isBefore(createdAt) ? createdAt : oldActivity;
        jdbcTemplate.update("UPDATE invoward.guest_sessions SET last_seen_at = ? WHERE id = ?",
                Timestamp.from(validStaleActivity), guest.guestSessionId());
    }

    private ExpiredProcessingGuest createExpiredProcessingGuest(AnalysisJobStatus initialJobStatus) {
        Instant createdAt = Instant.now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = createdAt.plus(1, ChronoUnit.HOURS);
        UUID guestSessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, guestSessionId, Timestamp.from(expiresAt), Timestamp.from(createdAt), Timestamp.from(createdAt));

        UUID analysisId = UUID.randomUUID();
        Instant uploadingAt = createdAt.plusSeconds(1);
        Instant classifyingAt = createdAt.plusSeconds(2);
        Analysis analysis = Analysis.create(
                        analysisId,
                        new GuestSessionOwner(guestSessionId, expiresAt),
                        PriceTolerance.exactMatch(),
                        createdAt)
                .transitionToUploading(uploadingAt)
                .beginClassification(classifyingAt);
        analysisRepository.create(analysis);

        AnalysisJob job = AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, createdAt)
                .awaitMoreUploads(uploadingAt)
                .queueClassification(classifyingAt);
        if (initialJobStatus == AnalysisJobStatus.RUNNING) {
            job = job.startQueuedAttempt(createdAt.plusSeconds(3), 3);
        }
        analysisJobRepository.create(job);
        return new ExpiredProcessingGuest(analysisId, guestSessionId, expiresAt);
    }

    private GuestRow guestRow(UUID guestSessionId) {
        return jdbcTemplate.queryForObject("""
                SELECT created_at, last_seen_at, expires_at
                FROM invoward.guest_sessions WHERE id = ?
                """, (result, rowNumber) -> new GuestRow(
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("last_seen_at").toInstant(),
                result.getTimestamp("expires_at").toInstant()), guestSessionId);
    }

    private Instant analysisExpiry(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.analyses WHERE id = ?",
                (result, rowNumber) -> result.getTimestamp(1).toInstant(),
                analysisId);
    }

    private String documentSha(UUID documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT sha256 FROM invoward.documents WHERE id = ?", String.class, documentId);
    }

    private long guestSessionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoward.guest_sessions", Long.class);
    }

    private EnumMap<FakeDocumentStorage.Operation, Integer> storageCallCounts() {
        EnumMap<FakeDocumentStorage.Operation, Integer> counts = new EnumMap<>(FakeDocumentStorage.Operation.class);
        for (FakeDocumentStorage.Operation operation : FakeDocumentStorage.Operation.values()) {
            counts.put(operation, storage.operationCalls(operation));
        }
        return counts;
    }

    private EnumMap<FakeReportStorage.Operation, Integer> reportCallCounts() {
        EnumMap<FakeReportStorage.Operation, Integer> counts = new EnumMap<>(FakeReportStorage.Operation.class);
        for (FakeReportStorage.Operation operation : FakeReportStorage.Operation.values()) {
            counts.put(operation, reportStorage.operationCalls(operation));
        }
        return counts;
    }

    private void assertNotFound(HttpResponse<String> response, String guestContext) {
        assertEquals(404, response.statusCode(), guestContext + ": " + response.body());
        assertEquals("application/json",
                response.headers().firstValue("Content-Type").orElseThrow().split(";", 2)[0]);
        assertEquals(ANALYSIS_NOT_FOUND, response.body(), guestContext);
        assertNoGuestCookieSet(response);
    }

    private void assertNoGuestCookieSet(HttpResponse<?> response) {
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                .noneMatch(value -> value.startsWith(GUEST_COOKIE + "=")),
                "Nested Analysis routes must never create or rotate the guest cookie.");
    }

    private static ExtractionDraft extractionDraft() {
        BigDecimal amount = new BigDecimal("10.0000");
        BigDecimal zero = BigDecimal.ZERO.setScale(4);
        return new ExtractionDraft(
                "Acme Supplies", "DOC-200", "2026-10-04", "USD", amount, zero, zero, amount,
                List.of(new ExtractionDraft.Line(
                        "ITEM-2", "Fastener pack", BigDecimal.ONE.setScale(4), "each", amount,
                        zero, zero, amount, 1, "Fastener pack 10.00",
                        new ExtractionDraft.RawBoundingBox(0.1, 0.2, 0.8, 0.9))));
    }

    private static byte[] validPdf(String title) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            PDDocumentInformation information = document.getDocumentInformation();
            information.setTitle(title);
            document.setDocumentInformation(information);
            document.save(output);
            return output.toByteArray();
        }
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private static String analysisPath(UUID analysisId) {
        return "/api/analyses/" + analysisId;
    }

    private static String cookies(String sessionCookie, String guestCookie) {
        List<String> values = new ArrayList<>();
        if (sessionCookie != null) {
            values.add(SESSION_COOKIE + "=" + sessionCookie);
        }
        if (guestCookie != null) {
            values.add(GUEST_COOKIE + "=" + guestCookie);
        }
        return String.join("; ", values);
    }

    private static String cookieValue(HttpResponse<?> response, String cookieName) {
        String prefix = cookieName + "=";
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a " + cookieName + " cookie."));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProviderConfiguration {

        @Bean
        @Primary
        FakeDocumentStorage fakeDocumentStorage() {
            return new FakeDocumentStorage();
        }

        @Bean
        @Primary
        FakeDocumentIntelligence fakeDocumentIntelligence() {
            return new FakeDocumentIntelligence();
        }

        @Bean
        @Primary
        FakeReportStorage fakeReportStorage() {
            return new FakeReportStorage();
        }

        @Bean
        @Primary
        ProcessingWakePort noOpProcessingWakePort() {
            return analysisId -> { };
        }
    }

    private record CsrfTicket(String sessionCookie, String token) {
    }

    private record GuestAnalysis(
            UUID analysisId,
            UUID guestSessionId,
            String guestCookie,
            Instant fixedExpiry,
            CsrfTicket csrf,
            String analysisResponseBody) {
    }

    private record GuestAccess(String label, CsrfTicket csrf, String guestCookie) {
    }

    private record GuestRow(Instant createdAt, Instant lastSeenAt, Instant expiresAt) {
    }

    private record ExpiredProcessingGuest(UUID analysisId, UUID guestSessionId, Instant expiresAt) {
    }
}
