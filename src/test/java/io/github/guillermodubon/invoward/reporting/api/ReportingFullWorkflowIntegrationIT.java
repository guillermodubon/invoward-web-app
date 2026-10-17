package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
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
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.domain.GeneratedReport;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeReportStorage;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs reconciliation and reporting over real PostgreSQL with only private report storage faked. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
@Import(ReportingFullWorkflowIntegrationIT.TestStorageConfiguration.class)
class ReportingFullWorkflowIntegrationIT {

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

    @Autowired
    private GeneratedReportRepository generatedReportRepository;

    @Autowired
    private FakeReportStorage reportStorage;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearFakeStorage() {
        reportStorage.clear();
    }

    @Test
    void persistedReconciliationFlowsThroughReportAndCurrentCsv() throws Exception {
        GuestSession guest = guestSessionService.create();
        GuestSessionOwner owner = new GuestSessionOwner(guest.id(), guest.expiresAt());
        UUID analysisId = createConfirmedAnalysis(owner);

        List<LineItemMatch> matches = runLineItemMatchingService.run(analysisId, owner);
        assertEquals(1, matches.size());
        assertEquals(LineMatchStatus.MATCHED, matches.getFirst().status());
        assertEquals(1, countRows("invoward.line_item_matches", analysisId));
        assertWorkflow(analysisId, owner, AnalysisStatus.RECONCILING);

        URI baseUri = applicationUri();
        CsrfTicket csrf = csrfTicket(baseUri);
        HttpResponse<String> reconciled = postWithoutBody(
                baseUri, "/api/analyses/" + analysisId + "/reconcile", csrf, guest.id());
        assertEquals(200, reconciled.statusCode(), reconciled.body());
        JsonNode reconciliation = objectMapper.readTree(reconciled.body());
        assertEquals("GENERATING_REPORT", reconciliation.get("analysisStatus").asString());
        assertEquals(1, reconciliation.get("summary").get("lineCount").asInt());
        assertEquals(1, reconciliation.get("summary").get("openDiscrepancyCount").asInt());
        JsonNode priceDiscrepancy = findDiscrepancy(reconciliation, "UNIT_PRICE_MISMATCH");
        UUID discrepancyId = UUID.fromString(priceDiscrepancy.get("id").asString());
        assertEquals(1, countRows("invoward.reconciliation_lines", analysisId));
        assertEquals(1, countRows("invoward.discrepancies", analysisId));
        assertWorkflow(analysisId, owner, AnalysisStatus.GENERATING_REPORT);

        HttpResponse<String> created = postWithoutBody(
                baseUri, reportPath(analysisId), csrf, guest.id());
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("/api/analyses/" + analysisId + "/report",
                created.headers().firstValue("Location").orElseThrow());
        assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());

        GeneratedReport persistedReport = generatedReportRepository.findByAnalysisId(analysisId).orElseThrow();
        assertEquals(1, countRows("invoward.generated_reports", analysisId));
        Instant persistedAnalysisExpiry = analysisRepository.findOwnedById(analysisId, owner, clock.instant())
                .orElseThrow().expiresAt();
        assertEquals(persistedAnalysisExpiry, persistedReport.expiresAt());
        assertTrue(persistedReport.storageKey().startsWith("guest/" + analysisId + "/reports/"));
        assertEquals(1, reportStorage.storedObjectCount());
        byte[] storedPdf = reportStorage.storedBytes(persistedReport.storageKey()).orElseThrow();
        assertTrue(new String(storedPdf, 0, Math.min(storedPdf.length, 8), StandardCharsets.US_ASCII)
                .startsWith("%PDF-"));
        assertEquals(storedPdf.length, persistedReport.sizeBytes());
        assertWorkflow(analysisId, owner, AnalysisStatus.COMPLETED);

        HttpResponse<String> read = get(baseUri, reportPath(analysisId), csrf, guest.id());
        assertEquals(200, read.statusCode(), read.body());
        assertEquals("no-store", read.headers().firstValue("Cache-Control").orElseThrow());
        JsonNode reportResponse = objectMapper.readTree(read.body());
        assertEquals(persistedReport.id().toString(), reportResponse.get("id").asString());
        assertEquals("application/pdf", reportResponse.get("contentType").asString());
        assertFalse(read.body().contains(persistedReport.storageKey()));
        assertFalse(read.body().contains(persistedReport.sha256()));

        String update = """
                {"expectedVersion":0,"resolutionStatus":"ACCEPTED",
                "resolutionReason":"APPROVED_CHANGE","resolutionNote":null}
                """;
        HttpResponse<String> updated = patchJson(baseUri,
                "/api/analyses/" + analysisId + "/discrepancies/" + discrepancyId,
                csrf, guest.id(), update);
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("ACCEPTED", objectMapper.readTree(updated.body()).get("resolutionStatus").asString());

        HttpResponse<String> repeated = postWithoutBody(
                baseUri, reportPath(analysisId), csrf, guest.id());
        assertEquals(200, repeated.statusCode(), repeated.body());
        assertEquals(persistedReport.id().toString(), objectMapper.readTree(repeated.body()).get("id").asString());
        assertEquals(1, countRows("invoward.generated_reports", analysisId));
        assertEquals(1, reportStorage.storedObjectCount(), "The persisted PDF snapshot must not be regenerated.");

        HttpResponse<String> csv = get(baseUri, csvPath(analysisId), csrf, guest.id());
        assertEquals(200, csv.statusCode(), csv.body());
        assertTrue(csv.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv;charset=UTF-8"));
        assertTrue(csv.headers().firstValue("Content-Disposition").orElseThrow()
                .contains("invoward-reconciliation-" + analysisId + ".csv"));
        assertEquals("no-store", csv.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(csv.body().contains("ACCEPTED,APPROVED_CHANGE"),
                "CSV must reflect the current persisted discrepancy resolution state.");
        assertTrue(csv.body().contains("Workflow widget"));
        assertEquals(1, countRows("invoward.generated_reports", analysisId));
        assertEquals(1, reportStorage.storedObjectCount());
        assertEquals(0, countRows("invoward.share_links", analysisId));
        assertWorkflow(analysisId, owner, AnalysisStatus.COMPLETED);
        assertNotNull(persistedReport.generatedAt());
    }

    private UUID createConfirmedAnalysis(GuestSessionOwner owner) {
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
        Instant reviewAt = start.plusSeconds(3);
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(reviewAt));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(reviewAt));

        Document reference = createDocument(analysis, DocumentRole.REFERENCE, DocumentType.QUOTE, start.plusSeconds(4));
        Document invoice = createDocument(analysis, DocumentRole.INVOICE, DocumentType.INVOICE, start.plusSeconds(4));
        createConfirmedExtraction(reference, "Reference report workflow evidence", new BigDecimal("10.0000"),
                start.plusSeconds(5));
        createConfirmedExtraction(invoice, "Invoice report workflow evidence", new BigDecimal("12.0000"),
                start.plusSeconds(5));

        Instant confirmedAt = start.plusSeconds(7);
        analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-REPORT-WORKFLOW", "I-REPORT-WORKFLOW", "Reporting Workflow Supplier", "USD",
                BigDecimal.TEN, BigDecimal.TEN, confirmedAt));
        analysisJobRepository.update(job.waitForMatching(confirmedAt));
        return analysisId;
    }

    private Document createDocument(Analysis analysis, DocumentRole role, DocumentType type, Instant createdAt) {
        return documentRepository.create(Document.createUploaded(
                        UUID.randomUUID(), analysis.id(), role, role + ".pdf", "application/pdf", 1024,
                        1, sha256(), "report-workflow/" + UUID.randomUUID(), analysis.expiresAt(), createdAt)
                .withDetectedType(type)
                .withConfirmedType(type));
    }

    private void createConfirmedExtraction(
            Document document, String sourceText, BigDecimal unitPrice, Instant createdAt) {
        List<ExtractedLineItem> lines = List.of(new ExtractedLineItem(
                0, "REPORT-WORKFLOW-001", "Workflow widget", BigDecimal.ONE, "each", unitPrice,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, 1, sourceText, null));
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Reporting Workflow Supplier", "INV-REPORT-WORKFLOW",
                java.time.LocalDate.of(2026, 10, 1), "USD", BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "reporting-workflow-v1", "test-model", 1, createdAt);
        Instant confirmedAt = createdAt.plusSeconds(1);
        extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
    }

    private void assertWorkflow(UUID analysisId, GuestSessionOwner owner, AnalysisStatus expectedStatus) {
        Analysis analysis = analysisRepository.findOwnedById(analysisId, owner, clock.instant()).orElseThrow();
        AnalysisJob job = analysisJobRepository.findByAnalysisId(analysisId).orElseThrow();
        assertEquals(expectedStatus, analysis.status());
        if (expectedStatus == AnalysisStatus.COMPLETED) {
            assertEquals(AnalysisJobStatus.COMPLETED, job.status());
            assertEquals(AnalysisStatus.COMPLETED, job.currentStage());
            assertNotNull(analysis.completedAt());
            assertNotNull(job.completedAt());
            assertFalse(job.retryable());
        } else {
            assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
            assertEquals(expectedStatus, job.currentStage());
            assertFalse(job.retryable());
        }
    }

    private long countRows(String table, UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE analysis_id = ?", Long.class, analysisId);
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

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private static JsonNode findDiscrepancy(JsonNode result, String type) {
        for (JsonNode discrepancy : result.get("discrepancies")) {
            if (type.equals(discrepancy.get("type").asString())) {
                return discrepancy;
            }
        }
        throw new AssertionError("Expected reconciliation discrepancy was absent.");
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

    private static String reportPath(UUID analysisId) {
        return "/api/analyses/" + analysisId + "/report";
    }

    private static String csvPath(UUID analysisId) {
        return "/api/analyses/" + analysisId + "/export.csv";
    }

    private record CsrfTicket(String sessionCookie, String token) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {

        @Bean
        @Primary
        FakeReportStorage fakeReportStorage(Clock clock) {
            return new FakeReportStorage(clock);
        }
    }
}
