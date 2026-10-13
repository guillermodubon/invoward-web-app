package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.PersistedExtraction;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import io.github.guillermodubon.invoward.identity.application.service.GuestSessionService;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.application.service.RunLineItemMatchingService;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises confirmed matching through persisted reconciliation and discrepancy review over PostgreSQL. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class ReconciliationFullWorkflowIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private GuestSessionService guestSessionService;

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
    private RunLineItemMatchingService runLineItemMatchingService;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void confirmedMatchingReconciliationReviewAndEvidencePersistAsOneWorkflow() throws Exception {
        GuestSession guest = guestSessionService.create();
        GuestSessionOwner owner = new GuestSessionOwner(guest.id(), guest.expiresAt());
        WorkflowFixture fixture = createMatchingFixture(owner);

        List<LineItemMatch> matches = runLineItemMatchingService.run(fixture.analysisId(), owner);
        assertEquals(1, matches.size());
        assertEquals(LineMatchStatus.MATCHED, matches.getFirst().status());
        assertEquals(LineMatchMethod.SKU, matches.getFirst().method());
        assertEquals(fixture.referenceLineItemId(), matches.getFirst().referenceLineItemId());
        assertEquals(fixture.invoiceLineItemId(), matches.getFirst().invoiceLineItemId());
        assertEquals(1, countRows("invoward.line_item_matches", fixture.analysisId()));
        assertWorkflow(fixture.analysisId(), owner, AnalysisStatus.RECONCILING);

        URI baseUri = applicationUri();
        CsrfTicket csrf = csrfTicket(baseUri);
        HttpResponse<String> reconcile = postWithoutBody(
                baseUri, "/api/analyses/" + fixture.analysisId() + "/reconcile", csrf, guest.id());
        assertEquals(200, reconcile.statusCode(), reconcile.body());
        JsonNode persistedResult = objectMapper.readTree(reconcile.body());
        assertEquals("GENERATING_REPORT", persistedResult.get("analysisStatus").asString());
        assertEquals("REVIEW_REQUIRED", persistedResult.get("reconciliationStatus").asString());
        assertEquals(1, persistedResult.get("summary").get("lineCount").asInt());
        assertEquals(1, persistedResult.get("summary").get("differentCount").asInt());
        assertEquals(1, persistedResult.get("summary").get("openDiscrepancyCount").asInt());
        assertEquals("DIFFERENT", persistedResult.get("lines").get(0).get("status").asString());

        JsonNode discrepancy = findDiscrepancy(persistedResult, "UNIT_PRICE_MISMATCH");
        assertEquals("OPEN", discrepancy.get("resolutionStatus").asString());
        UUID discrepancyId = UUID.fromString(discrepancy.get("id").asString());

        assertEquals(1, countRows("invoward.reconciliation_lines", fixture.analysisId()));
        assertEquals(1, countRows("invoward.discrepancies", fixture.analysisId()));
        assertEquals(2, countEvidenceRows(discrepancyId));
        assertEquals(0, countRows("invoward.generated_reports", fixture.analysisId()));
        assertPersistedFinancialSnapshot(fixture.analysisId());
        assertWorkflow(fixture.analysisId(), owner, AnalysisStatus.GENERATING_REPORT);

        HttpResponse<String> readBeforeReview = get(
                baseUri, "/api/analyses/" + fixture.analysisId() + "/reconciliation", csrf, guest.id());
        assertEquals(200, readBeforeReview.statusCode(), readBeforeReview.body());
        JsonNode readResult = objectMapper.readTree(readBeforeReview.body());
        assertEquals(persistedResult.get("lines"), readResult.get("lines"));
        assertEquals("OPEN", findDiscrepancy(readResult, "UNIT_PRICE_MISMATCH")
                .get("resolutionStatus").asString());

        String patchBody = """
                {"expectedVersion":0,"resolutionStatus":"ACCEPTED",
                "resolutionReason":"APPROVED_CHANGE","resolutionNote":null}
                """;
        HttpResponse<String> update = patchJson(
                baseUri,
                "/api/analyses/" + fixture.analysisId() + "/discrepancies/" + discrepancyId,
                csrf,
                guest.id(),
                patchBody);
        assertEquals(200, update.statusCode(), update.body());
        JsonNode updatedDiscrepancy = objectMapper.readTree(update.body());
        assertEquals("ACCEPTED", updatedDiscrepancy.get("resolutionStatus").asString());
        assertEquals(1, updatedDiscrepancy.get("version").asInt());

        HttpResponse<String> readAfterReview = get(
                baseUri, "/api/analyses/" + fixture.analysisId() + "/reconciliation", csrf, guest.id());
        assertEquals(200, readAfterReview.statusCode(), readAfterReview.body());
        JsonNode currentResult = objectMapper.readTree(readAfterReview.body());
        assertEquals("REVIEW_REQUIRED", currentResult.get("reconciliationStatus").asString(),
                "Resolution must not rewrite the original reconciliation result.");
        assertEquals("ACCEPTED", findDiscrepancy(currentResult, "UNIT_PRICE_MISMATCH")
                .get("resolutionStatus").asString());
        assertEquals(1, currentResult.get("summary").get("acceptedDiscrepancyCount").asInt());

        HttpResponse<String> evidenceResponse = get(
                baseUri,
                "/api/analyses/" + fixture.analysisId() + "/discrepancies/" + discrepancyId + "/evidence",
                csrf,
                guest.id());
        assertEquals(200, evidenceResponse.statusCode(), evidenceResponse.body());
        JsonNode evidence = objectMapper.readTree(evidenceResponse.body());
        assertEquals(2, evidence.size());
        assertEquals(List.of("INVOICE", "REFERENCE"), evidence.valueStream()
                .map(item -> item.get("side").asString()).sorted().toList());
        assertTrue(evidenceResponse.body().contains("Reference workflow source evidence"));
        assertTrue(evidenceResponse.body().contains("Invoice workflow source evidence"));
        assertFalse(evidenceResponse.body().contains("storageKey"));

        assertEquals(2, countEvidenceRows(discrepancyId));
        assertEquals(0, countRows("invoward.generated_reports", fixture.analysisId()));
        assertWorkflow(fixture.analysisId(), owner, AnalysisStatus.GENERATING_REPORT);
    }

    private WorkflowFixture createMatchingFixture(GuestSessionOwner owner) {
        Instant start = clock.instant().minusSeconds(30);
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), start));
        AnalysisJob job = analysisJobRepository.create(
                AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, start));

        Instant uploadAt = start.plusSeconds(1);
        analysis = analysisRepository.update(analysis.transitionToUploading(uploadAt));
        job = analysisJobRepository.update(job.awaitMoreUploads(uploadAt));
        Instant classificationAt = start.plusSeconds(2);
        analysis = analysisRepository.update(analysis.markClassifying(classificationAt));
        job = analysisJobRepository.update(job.waitForUserAtClassification(classificationAt));
        Instant extractionReviewAt = start.plusSeconds(3);
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(extractionReviewAt));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(extractionReviewAt));

        Document reference = createDocument(analysis, DocumentRole.REFERENCE, DocumentType.QUOTE, start.plusSeconds(4));
        Document invoice = createDocument(analysis, DocumentRole.INVOICE, DocumentType.INVOICE, start.plusSeconds(4));
        UUID referenceLineId = createConfirmedExtraction(
                reference, "Reference workflow source evidence", new BigDecimal("10.0000"), start.plusSeconds(5));
        UUID invoiceLineId = createConfirmedExtraction(
                invoice, "Invoice workflow source evidence", new BigDecimal("12.0000"), start.plusSeconds(5));

        Instant extractionConfirmedAt = start.plusSeconds(7);
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-WORKFLOW-1", "I-WORKFLOW-1", "Workflow Test Supplier", "USD",
                BigDecimal.TEN, BigDecimal.TEN, extractionConfirmedAt));
        analysisJobRepository.update(job.waitForMatching(extractionConfirmedAt));
        return new WorkflowFixture(analysisId, owner, referenceLineId, invoiceLineId);
    }

    private Document createDocument(Analysis analysis, DocumentRole role, DocumentType type, Instant createdAt) {
        return documentRepository.create(Document.createUploaded(
                        UUID.randomUUID(), analysis.id(), role, role + ".pdf", "application/pdf", 1024,
                        1, sha256(), "reconciliation-workflow/" + UUID.randomUUID(),
                        analysis.expiresAt(), createdAt)
                .withDetectedType(type)
                .withConfirmedType(type));
    }

    private UUID createConfirmedExtraction(
            Document document, String sourceText, BigDecimal unitPrice, Instant createdAt) {
        List<ExtractedLineItem> lines = List.of(new ExtractedLineItem(
                0, "WORKFLOW-001", "Workflow widget", BigDecimal.ONE, "each", unitPrice,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, 1, sourceText, null));
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Workflow Test Supplier", null, null, "USD",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "reconciliation-workflow-v1", "test-model", 1, createdAt);
        Instant confirmedAt = createdAt.plusSeconds(1);
        PersistedExtraction confirmed = extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return confirmed.lineItemIds().getFirst();
    }

    private void assertWorkflow(UUID analysisId, GuestSessionOwner owner, AnalysisStatus expectedStage) {
        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, clock.instant()).orElseThrow();
        AnalysisJob job = analysisJobRepository.findByAnalysisId(analysisId).orElseThrow();
        assertEquals(expectedStage, analysis.status());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(expectedStage, job.currentStage());
        assertFalse(job.retryable());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
        if (expectedStage == AnalysisStatus.GENERATING_REPORT) {
            assertEquals(AnalysisReconciliationStatus.REVIEW_REQUIRED, analysis.reconciliationStatus());
        }
    }

    private void assertPersistedFinancialSnapshot(UUID analysisId) {
        Integer matchesSnapshot = jdbcTemplate.queryForObject("""
                SELECT CASE WHEN status = 'DIFFERENT'
                            AND reference_unit_price = 10.0000
                            AND invoice_unit_price = 12.0000
                            AND unit_price_difference = 2.0000
                       THEN 1 ELSE 0 END
                FROM invoward.reconciliation_lines
                WHERE analysis_id = ?
                """, Integer.class, analysisId);
        assertEquals(1, matchesSnapshot);
    }

    private long countRows(String table, UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE analysis_id = ?", Long.class, analysisId);
    }

    private long countEvidenceRows(UUID discrepancyId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.discrepancy_evidence WHERE discrepancy_id = ?",
                Long.class,
                discrepancyId);
    }

    private CsrfTicket csrfTicket(URI baseUri) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", null, null);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode csrf = objectMapper.readTree(response.body());
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        assertFalse(csrf.get("token").asString().isBlank());
        return new CsrfTicket(cookieValue(response, SESSION_COOKIE), csrf.get("token").asString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, CsrfTicket csrf, UUID guestSessionId) throws Exception {
        return httpClient.send(request(baseUri, path, csrf, guestSessionId).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutBody(
            URI baseUri, String path, CsrfTicket csrf, UUID guestSessionId) throws Exception {
        return httpClient.send(request(baseUri, path, csrf, guestSessionId)
                        .header("X-CSRF-TOKEN", csrf.token())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(
            URI baseUri, String path, CsrfTicket csrf, UUID guestSessionId, String body) throws Exception {
        return httpClient.send(request(baseUri, path, csrf, guestSessionId)
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf.token())
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(
            URI baseUri, String path, CsrfTicket csrf, UUID guestSessionId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(REQUEST_TIMEOUT);
        List<String> cookies = new ArrayList<>();
        if (csrf != null) {
            cookies.add(SESSION_COOKIE + "=" + csrf.sessionCookie());
        }
        if (guestSessionId != null) {
            cookies.add(GUEST_COOKIE + "=" + guestSessionId);
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", String.join("; ", cookies));
        }
        return request;
    }

    private static JsonNode findDiscrepancy(JsonNode result, String type) {
        for (JsonNode discrepancy : result.get("discrepancies")) {
            if (type.equals(discrepancy.get("type").asString())) {
                return discrepancy;
            }
        }
        throw new AssertionError("Expected reconciliation discrepancy was absent.");
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
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

    private static String sha256() {
        String value = UUID.randomUUID().toString().replace("-", "");
        return value + value;
    }

    private record CsrfTicket(String sessionCookie, String token) {
    }

    private record WorkflowFixture(
            UUID analysisId,
            GuestSessionOwner owner,
            UUID referenceLineItemId,
            UUID invoiceLineItemId) {
    }
}
