package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.service.GetExtractionService;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionStatus;
import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import io.github.guillermodubon.invoward.identity.application.service.GuestSessionService;
import io.github.guillermodubon.invoward.reconciliation.application.model.DuplicateInvoiceWarning;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationSummary;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.export.CsvExportGenerator;
import io.github.guillermodubon.invoward.reporting.application.model.CsvExport;
import io.github.guillermodubon.invoward.reporting.application.model.GeneratedReportArtifact;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.application.port.ReportArtifactGenerator;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeReportStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies authenticated report and CSV access through real HTTP sessions and PostgreSQL ownership. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
@Import(ReportingRegisteredOwnershipIntegrationIT.TestStorageConfiguration.class)
class ReportingRegisteredOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String RAW_PASSWORD = "report ownership integration passphrase";
    private static final String ANALYSIS_NOT_FOUND =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";
    private static final byte[] PDF_BYTES =
            "%PDF-1.7\nsynthetic ownership fixture\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CSV_BYTES = "Scope,Line Status\r\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private GuestSessionService guestSessionService;

    @Autowired
    private FakeReportStorage reportStorage;

    @MockitoBean
    private GetReconciliationService reconciliationService;

    @MockitoBean
    private GetExtractionService extractionService;

    @MockitoBean
    private ReportArtifactGenerator artifactGenerator;

    @MockitoBean
    private CsvExportGenerator csvExportGenerator;

    @MockitoSpyBean
    private GeneratedReportRepository generatedReportRepository;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearFakeStorage() {
        reportStorage.clear();
    }

    @Test
    void reportOwnerSucceedsAndOtherRegisteredUserGetsSameNotFoundWithoutDownstreamWork() throws Exception {
        UserFixture userA = insertActiveVerifiedUser("report-owner-a");
        UserFixture userB = insertActiveVerifiedUser("report-owner-b");
        ReportFixture fixture = createReportReadyAnalysis(userA.id());
        GuestSession unrelatedGuest = guestSessionService.create();
        Instant oldLastSeen = clock.instant().minus(Duration.ofMinutes(2));
        jdbcTemplate.update("UPDATE invoward.guest_sessions SET created_at = ?, last_seen_at = ? WHERE id = ?",
                java.sql.Timestamp.from(oldLastSeen), java.sql.Timestamp.from(oldLastSeen), unrelatedGuest.id());
        Instant guestLastSeenBefore = guestLastSeen(unrelatedGuest.id());
        stubReportInputs(fixture);

        URI baseUri = applicationUri();
        AuthenticatedSession sessionA = login(baseUri, userA);
        AuthenticatedSession sessionB = login(baseUri, userB);

        HttpResponse<String> postReport = postWithoutBody(
                baseUri, reportPath(fixture.analysisId()), sessionA, unrelatedGuest.id());
        assertEquals(201, postReport.statusCode());
        assertEquals("/api/analyses/" + fixture.analysisId() + "/report",
                postReport.headers().firstValue("Location").orElseThrow());
        assertEquals("application/json", mediaType(postReport));
        JsonNode createdReport = objectMapper.readTree(postReport.body());
        assertEquals("application/pdf", createdReport.get("contentType").asString());
        assertTrue(createdReport.hasNonNull("downloadUrl"));

        HttpResponse<String> getReport = get(
                baseUri, reportPath(fixture.analysisId()), sessionA.cookie(), unrelatedGuest.id());
        assertEquals(200, getReport.statusCode());
        assertEquals(createdReport.get("id").asString(),
                objectMapper.readTree(getReport.body()).get("id").asString());
        assertEquals("no-store", getReport.headers().firstValue("Cache-Control").orElseThrow());

        HttpResponse<String> export = get(
                baseUri, csvPath(fixture.analysisId()), sessionA.cookie(), unrelatedGuest.id());
        assertEquals(200, export.statusCode());
        assertTrue(export.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv"));
        assertTrue(export.headers().firstValue("Content-Disposition").orElseThrow()
                .contains("invoward-reconciliation-" + fixture.analysisId() + ".csv"));
        assertEquals("no-store", export.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(guestLastSeenBefore, guestLastSeen(unrelatedGuest.id()),
                "Authenticated access must ignore the accompanying guest cookie.");

        int storesBeforeUnauthorizedRequests = reportStorage.operationCalls(FakeReportStorage.Operation.STORE);
        int deletesBeforeUnauthorizedRequests = reportStorage.operationCalls(FakeReportStorage.Operation.DELETE);
        int presignsBeforeUnauthorizedRequests = reportStorage.operationCalls(FakeReportStorage.Operation.PRESIGN);
        clearInvocations(reconciliationService, extractionService, artifactGenerator,
                csvExportGenerator, generatedReportRepository);

        HttpResponse<String> userBPost = postWithoutBody(
                baseUri, reportPath(fixture.analysisId()), sessionB, null);
        HttpResponse<String> userBGetReport = get(
                baseUri, reportPath(fixture.analysisId()), sessionB.cookie(), null);
        HttpResponse<String> userBExport = get(
                baseUri, csvPath(fixture.analysisId()), sessionB.cookie(), null);

        assertAnalysisNotFound(userBPost);
        assertAnalysisNotFound(userBGetReport);
        assertAnalysisNotFound(userBExport);
        assertEquals(storesBeforeUnauthorizedRequests,
                reportStorage.operationCalls(FakeReportStorage.Operation.STORE));
        assertEquals(deletesBeforeUnauthorizedRequests,
                reportStorage.operationCalls(FakeReportStorage.Operation.DELETE));
        assertEquals(presignsBeforeUnauthorizedRequests,
                reportStorage.operationCalls(FakeReportStorage.Operation.PRESIGN));
        verifyNoInteractions(reconciliationService, extractionService, artifactGenerator,
                csvExportGenerator, generatedReportRepository);
    }

    private ReportFixture createReportReadyAnalysis(UUID userId) {
        Instant startedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                .minusSeconds(60);
        AnalysisOwner owner = new RegisteredUserOwner(userId);
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), startedAt));
        AnalysisJob job = analysisJobRepository.create(
                AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, startedAt));
        analysis = analysisRepository.update(analysis.transitionToUploading(startedAt.plusSeconds(1)));
        job = analysisJobRepository.update(job.awaitMoreUploads(startedAt.plusSeconds(1)));
        analysis = analysisRepository.update(analysis.markClassifying(startedAt.plusSeconds(2)));
        job = analysisJobRepository.update(job.waitForUserAtClassification(startedAt.plusSeconds(2)));
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(startedAt.plusSeconds(3)));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(startedAt.plusSeconds(3)));
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "REPORT-REFERENCE", "REPORT-INVOICE", "Reporting Ownership Vendor", "USD",
                new BigDecimal("100.0000"), new BigDecimal("110.0000"), startedAt.plusSeconds(4)));
        job = analysisJobRepository.update(job.waitForMatching(startedAt.plusSeconds(4)));
        analysis = analysisRepository.update(analysis.beginReconciliation(startedAt.plusSeconds(5)));
        job = analysisJobRepository.update(job.waitForReconciliation(startedAt.plusSeconds(5)));
        analysis = analysisRepository.update(analysis.completeReconciliation(
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                new BigDecimal("10.0000"), startedAt.plusSeconds(6)));
        job = analysisJobRepository.update(job.awaitReportGeneration(startedAt.plusSeconds(6)));
        assertEquals(AnalysisStatus.GENERATING_REPORT, analysis.status());
        return new ReportFixture(analysisId, owner, analysis);
    }

    private void stubReportInputs(ReportFixture fixture) throws IOException {
        Analysis analysis = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        ReconciliationResult reconciliation = new ReconciliationResult(
                analysis.id(), analysis.status(), analysis.reconciliationStatus(),
                analysis.priceTolerance(), analysis.referenceTotal(),
                analysis.invoicedTotal(), analysis.difference(),
                DuplicateInvoiceWarning.none(), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of());
        when(reconciliationService.get(analysis.id(), analysis.owner())).thenReturn(reconciliation);
        when(extractionService.get(fixture.analysisId(), fixture.owner())).thenReturn(confirmedExtractionReview());
        when(artifactGenerator.generate(any())).thenAnswer(invocation -> {
            Path artifactPath = Files.createTempFile(temporaryDirectory, "report-ownership-", ".pdf");
            Files.write(artifactPath, PDF_BYTES);
            return GeneratedReportArtifact.validated(artifactPath, PDF_BYTES.length, sha256(PDF_BYTES));
        });
        when(csvExportGenerator.generate(any())).thenReturn(new CsvExport(CSV_BYTES));
    }

    private UserFixture insertActiveVerifiedUser(String prefix) {
        UUID id = UUID.randomUUID();
        String email = prefix + "-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Reporting Ownership User', ?, ?, true, 'ACTIVE')
                """, id, email, passwordEncoder.encode(RAW_PASSWORD));
        return new UserFixture(id, email);
    }

    private AuthenticatedSession login(URI baseUri, UserFixture user) throws Exception {
        CsrfTicket preAuthentication = csrfTicket(baseUri, null);
        HttpResponse<String> response = postJson(
                baseUri, "/api/auth/login", preAuthentication.sessionCookie(), preAuthentication.token(),
                objectMapper.writeValueAsString(Map.of("email", user.email(), "password", RAW_PASSWORD)));
        assertEquals(200, response.statusCode());
        String authenticatedCookie = cookieValue(response, SESSION_COOKIE);
        assertNotEquals(preAuthentication.sessionCookie(), authenticatedCookie,
                "Login must rotate the pre-authentication session ID.");
        CsrfTicket authenticatedCsrf = csrfTicket(baseUri, authenticatedCookie);
        return new AuthenticatedSession(authenticatedCsrf.sessionCookie(), authenticatedCsrf.token());
    }

    private CsrfTicket csrfTicket(URI baseUri, String sessionCookie) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", sessionCookie, null);
        assertEquals(200, response.statusCode());
        JsonNode csrf = objectMapper.readTree(response.body());
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        assertFalse(csrf.get("token").asString().isBlank());
        return new CsrfTicket(updatedCookieValue(response, SESSION_COOKIE, sessionCookie),
                csrf.get("token").asString());
    }

    private HttpResponse<String> postJson(
            URI baseUri, String path, String sessionCookie, String csrfToken, String body) throws Exception {
        return httpClient.send(request(baseUri, path, sessionCookie, null)
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrfToken)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutBody(
            URI baseUri, String path, AuthenticatedSession session, UUID guestSessionId) throws Exception {
        return httpClient.send(request(baseUri, path, session.cookie(), guestSessionId)
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, String sessionCookie, UUID guestSessionId) throws Exception {
        return httpClient.send(request(baseUri, path, sessionCookie, guestSessionId).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(
            URI baseUri, String path, String sessionCookie, UUID guestSessionId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10));
        StringBuilder cookies = new StringBuilder();
        if (sessionCookie != null) {
            cookies.append(SESSION_COOKIE).append('=').append(sessionCookie);
        }
        if (guestSessionId != null) {
            if (!cookies.isEmpty()) {
                cookies.append("; ");
            }
            cookies.append(GUEST_COOKIE).append('=').append(guestSessionId);
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookies.toString());
        }
        return request;
    }

    private void assertAnalysisNotFound(HttpResponse<String> response) {
        assertEquals(404, response.statusCode());
        assertEquals("application/json", mediaType(response));
        assertEquals(ANALYSIS_NOT_FOUND, response.body());
    }

    private Instant guestLastSeen(UUID guestSessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_seen_at FROM invoward.guest_sessions WHERE id = ?",
                (result, row) -> result.getTimestamp("last_seen_at").toInstant(), guestSessionId);
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private static String reportPath(UUID analysisId) {
        return "/api/analyses/" + analysisId + "/report";
    }

    private static String csvPath(UUID analysisId) {
        return "/api/analyses/" + analysisId + "/export.csv";
    }

    private static String mediaType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElseThrow().split(";", 2)[0];
    }

    private static String cookieValue(HttpResponse<?> response, String cookieName) {
        String prefix = cookieName + "=";
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .findFirst()
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new AssertionError("Expected a " + cookieName + " cookie."));
    }

    private static String updatedCookieValue(HttpResponse<?> response, String cookieName, String fallback) {
        String prefix = cookieName + "=";
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .findFirst()
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .orElse(fallback);
    }

    private static ExtractionReview confirmedExtractionReview() {
        LocalDate date = LocalDate.of(2026, 10, 1);
        return new ExtractionReview(
                reviewedDocument(DocumentRole.REFERENCE, DocumentType.QUOTE, "REPORT-REFERENCE", date),
                reviewedDocument(DocumentRole.INVOICE, DocumentType.INVOICE, "REPORT-INVOICE", date));
    }

    private static ExtractionReview.ReviewedDocument reviewedDocument(
            DocumentRole role, DocumentType type, String number, LocalDate date) {
        ExtractedDocument extraction = new ExtractedDocument(
                ExtractionStatus.CONFIRMED, ExtractionSource.AI, Instant.parse("2026-10-01T12:00:00Z"),
                "Reporting Ownership Vendor", number, date, "USD", null, null, null, null, List.of());
        return new ExtractionReview.ReviewedDocument(
                UUID.randomUUID(), UUID.randomUUID(), role, type, 0, List.of(), extraction);
    }

    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }

    private record UserFixture(UUID id, String email) {
    }

    private record CsrfTicket(String sessionCookie, String token) {
    }

    private record AuthenticatedSession(String cookie, String csrfToken) {
    }

    private record ReportFixture(UUID analysisId, AnalysisOwner owner, Analysis analysis) {
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
