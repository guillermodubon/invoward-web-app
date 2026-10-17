package io.github.guillermodubon.invoward.reporting.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises guest-owned reporting over real HTTP, CSRF, and PostgreSQL with fake report storage. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
@Import(ReportingGuestOwnershipIntegrationIT.TestStorageConfiguration.class)
class ReportingGuestOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String ANALYSIS_NOT_FOUND =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";
    private static final byte[] PDF_BYTES =
            "%PDF-1.7\nsynthetic guest report fixture\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CSV_BYTES = "Scope,Line Status\r\nLINE,MATCHED\r\n".getBytes(StandardCharsets.UTF_8);

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

    @MockitoSpyBean
    private GuestSessionService guestSessionServiceSpy;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearFakeStorage() {
        reportStorage.clear();
    }

    @Test
    void guestOwnerCanGenerateReadAndExportWithoutSlidingFixedExpiry() throws Exception {
        GuestSession guest = createGuestSession(Duration.ofMinutes(3));
        ReportFixture fixture = createReportReadyAnalysis(guest);
        stubReportInputs(fixture);
        CsrfTicket csrf = csrfTicket(applicationUri());
        long guestSessionsBeforeRequests = guestSessionCount();
        Instant initialLastSeen = guestLastSeen(guest.id());

        HttpResponse<String> missingCsrf = postReportWithoutCsrf(
                applicationUri(), fixture.analysisId(), csrf.sessionCookie(), guest.id().toString());
        assertEquals(403, missingCsrf.statusCode());
        assertEquals("{\"code\":\"CSRF_INVALID\",\"message\":\"The request could not be validated.\"}",
                missingCsrf.body());
        assertEquals(initialLastSeen, guestLastSeen(guest.id()),
                "Rejected CSRF requests must not record guest activity.");
        assertEquals(0, reportStorage.operationCalls(FakeReportStorage.Operation.STORE));

        HttpResponse<String> created = postReport(
                applicationUri(), fixture.analysisId(), csrf, guest.id().toString());
        assertEquals(201, created.statusCode());
        assertEquals("/api/analyses/" + fixture.analysisId() + "/report",
                created.headers().firstValue("Location").orElseThrow());
        JsonNode createdBody = objectMapper.readTree(created.body());
        assertEquals(guest.expiresAt().toString(), createdBody.get("expiresAt").asString());
        assertExpiryCapped(createdBody, guest.expiresAt());
        assertFalse(hasGuestCookieSet(created), "Nested report routes must not mint or rotate guest cookies.");
        assertEquals(guest.expiresAt(), persistedReportExpiry(fixture.analysisId()));

        HttpResponse<String> read = get(
                applicationUri(), reportPath(fixture.analysisId()), csrf.sessionCookie(), guest.id().toString());
        assertEquals(200, read.statusCode());
        assertEquals("no-store", read.headers().firstValue("Cache-Control").orElseThrow());
        assertExpiryCapped(objectMapper.readTree(read.body()), guest.expiresAt());

        HttpResponse<String> export = get(
                applicationUri(), csvPath(fixture.analysisId()), csrf.sessionCookie(), guest.id().toString());
        assertEquals(200, export.statusCode());
        assertTrue(export.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv"));
        assertEquals(new String(CSV_BYTES, StandardCharsets.UTF_8), export.body());
        assertEquals("no-store", export.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(hasGuestCookieSet(read));
        assertFalse(hasGuestCookieSet(export));

        assertEquals(guest.expiresAt(), guestSession(guest.id()).expiresAt(),
                "Successful nested actions must not slide the fixed guest expiry.");
        assertTrue(guestLastSeen(guest.id()).isAfter(initialLastSeen),
                "Successful guest actions should record activity at the HTTP boundary.");
        assertEquals(guestSessionsBeforeRequests, guestSessionCount(),
                "Nested reporting routes must reuse, not create, a guest session.");
        assertEquals(1, reportStorage.storedObjectCount());
        assertEquals(2, reportStorage.operationCalls(FakeReportStorage.Operation.PRESIGN));
    }

    @Test
    void otherMissingMalformedUnknownAndExpiredGuestOwnersReceiveUniformNotFoundWithoutWork()
            throws Exception {
        GuestSession guestA = createGuestSession(Duration.ofMinutes(3));
        ReportFixture guestAAnalysis = createReportReadyAnalysis(guestA);
        stubReportInputs(guestAAnalysis);
        CsrfTicket csrf = csrfTicket(applicationUri());
        HttpResponse<String> reportCreated = postReport(
                applicationUri(), guestAAnalysis.analysisId(), csrf, guestA.id().toString());
        assertEquals(201, reportCreated.statusCode());

        GuestSession guestB = createGuestSession(Duration.ofMinutes(10));
        Instant guestBLastSeen = guestLastSeen(guestB.id());
        GuestSession expiredGuest = createGuestSession(Duration.ofMinutes(3));
        ReportFixture expiredAnalysis = createReportReadyAnalysis(expiredGuest);
        expireGuestAndAnalysis(expiredGuest.id(), expiredAnalysis.analysisId());

        long guestSessionsBeforeDeniedRequests = guestSessionCount();
        Instant guestALastSeen = guestLastSeen(guestA.id());
        Instant expiredLastSeen = guestLastSeen(expiredGuest.id());
        int stores = reportStorage.operationCalls(FakeReportStorage.Operation.STORE);
        int deletes = reportStorage.operationCalls(FakeReportStorage.Operation.DELETE);
        int presigns = reportStorage.operationCalls(FakeReportStorage.Operation.PRESIGN);
        clearInvocations(reconciliationService, extractionService, artifactGenerator,
                csvExportGenerator, generatedReportRepository, guestSessionServiceSpy);

        List<DeniedOwner> deniedOwners = List.of(
                new DeniedOwner("another active guest", guestAAnalysis.analysisId(), guestB.id().toString()),
                new DeniedOwner("missing cookie", guestAAnalysis.analysisId(), null),
                new DeniedOwner("malformed cookie", guestAAnalysis.analysisId(), "not-a-uuid"),
                new DeniedOwner("unknown cookie", guestAAnalysis.analysisId(), UUID.randomUUID().toString()),
                new DeniedOwner("expired owner cookie", expiredAnalysis.analysisId(), expiredGuest.id().toString()));

        for (DeniedOwner deniedOwner : deniedOwners) {
            HttpResponse<String> post = postReport(
                    applicationUri(), deniedOwner.analysisId(), csrf, deniedOwner.cookie());
            assertAnalysisNotFound(post, deniedOwner.label() + " POST");
            assertFalse(hasGuestCookieSet(post), deniedOwner.label() + " POST must not create a guest cookie.");

            HttpResponse<String> read = get(applicationUri(), reportPath(deniedOwner.analysisId()),
                    csrf.sessionCookie(), deniedOwner.cookie());
            assertAnalysisNotFound(read, deniedOwner.label() + " report GET");

            HttpResponse<String> export = get(applicationUri(), csvPath(deniedOwner.analysisId()),
                    csrf.sessionCookie(), deniedOwner.cookie());
            assertAnalysisNotFound(export, deniedOwner.label() + " CSV GET");
        }

        verifyNoInteractions(reconciliationService, extractionService, artifactGenerator,
                csvExportGenerator, generatedReportRepository);
        verify(guestSessionServiceSpy, never()).create();
        assertEquals(stores, reportStorage.operationCalls(FakeReportStorage.Operation.STORE));
        assertEquals(deletes, reportStorage.operationCalls(FakeReportStorage.Operation.DELETE));
        assertEquals(presigns, reportStorage.operationCalls(FakeReportStorage.Operation.PRESIGN));
        assertEquals(guestSessionsBeforeDeniedRequests, guestSessionCount(),
                "Invalid nested guest requests must never create replacement sessions.");
        assertEquals(guestALastSeen, guestLastSeen(guestA.id()));
        assertEquals(guestBLastSeen, guestLastSeen(guestB.id()));
        assertEquals(expiredLastSeen, guestLastSeen(expiredGuest.id()));
    }

    private GuestSession createGuestSession(Duration remainingLifetime) {
        GuestSession created = guestSessionService.create();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant createdAt = now.minus(Duration.ofMinutes(2));
        Instant lastSeenAt = now.minus(Duration.ofMinutes(1));
        Instant expiresAt = now.plus(remainingLifetime);
        jdbcTemplate.update("""
                        UPDATE invoward.guest_sessions
                        SET created_at = ?, last_seen_at = ?, expires_at = ?
                        WHERE id = ?
                        """,
                Timestamp.from(createdAt), Timestamp.from(lastSeenAt), Timestamp.from(expiresAt), created.id());
        return guestSessionService.findActive(created.id()).orElseThrow();
    }

    private ReportFixture createReportReadyAnalysis(GuestSession guestSession) {
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS).minusSeconds(60);
        AnalysisOwner owner = new GuestSessionOwner(guestSession.id(), guestSession.expiresAt());
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
                "QUOTE", "GUEST-REFERENCE", "GUEST-INVOICE", "Guest Reporting Vendor", "USD",
                new BigDecimal("100.0000"), new BigDecimal("110.0000"), startedAt.plusSeconds(4)));
        job = analysisJobRepository.update(job.waitForMatching(startedAt.plusSeconds(4)));
        analysis = analysisRepository.update(analysis.beginReconciliation(startedAt.plusSeconds(5)));
        job = analysisJobRepository.update(job.waitForReconciliation(startedAt.plusSeconds(5)));
        analysis = analysisRepository.update(analysis.completeReconciliation(
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                new BigDecimal("10.0000"), startedAt.plusSeconds(6)));
        job = analysisJobRepository.update(job.awaitReportGeneration(startedAt.plusSeconds(6)));
        assertEquals(AnalysisStatus.GENERATING_REPORT, analysis.status());
        return new ReportFixture(analysisId, owner);
    }

    private void stubReportInputs(ReportFixture fixture) throws IOException {
        Analysis analysis = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        ReconciliationResult reconciliation = new ReconciliationResult(
                analysis.id(), analysis.status(), analysis.reconciliationStatus(), analysis.priceTolerance(),
                analysis.referenceTotal(), analysis.invoicedTotal(), analysis.difference(),
                DuplicateInvoiceWarning.none(), new ReconciliationSummary(0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of());
        when(reconciliationService.get(analysis.id(), analysis.owner())).thenReturn(reconciliation);
        when(extractionService.get(fixture.analysisId(), fixture.owner())).thenReturn(confirmedExtractionReview());
        when(artifactGenerator.generate(any())).thenAnswer(invocation -> {
            Path artifactPath = Files.createTempFile(temporaryDirectory, "guest-report-", ".pdf");
            Files.write(artifactPath, PDF_BYTES);
            return GeneratedReportArtifact.validated(artifactPath, PDF_BYTES.length, sha256(PDF_BYTES));
        });
        when(csvExportGenerator.generate(any())).thenReturn(new CsvExport(CSV_BYTES));
    }

    private void expireGuestAndAnalysis(UUID guestSessionId, UUID analysisId) {
        Instant expiresAt = clock.instant().truncatedTo(ChronoUnit.MICROS).minusSeconds(1);
        Instant createdAt = expiresAt.minus(Duration.ofMinutes(1));
        jdbcTemplate.update("""
                        UPDATE invoward.guest_sessions
                        SET created_at = ?, last_seen_at = ?, expires_at = ?
                        WHERE id = ?
                        """,
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(expiresAt), guestSessionId);
        jdbcTemplate.update("UPDATE invoward.analyses SET expires_at = ? WHERE id = ?",
                Timestamp.from(expiresAt), analysisId);
    }

    private HttpResponse<String> postReport(URI baseUri, UUID analysisId, CsrfTicket csrf, String guestCookie)
            throws Exception {
        HttpRequest.Builder request = request(baseUri, reportPath(analysisId), csrf.sessionCookie(), guestCookie)
                .header("X-CSRF-TOKEN", csrf.token())
                .header("Content-Type", "application/json");
        return httpClient.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postReportWithoutCsrf(
            URI baseUri, UUID analysisId, String sessionCookie, String guestCookie) throws Exception {
        HttpRequest.Builder request = request(baseUri, reportPath(analysisId), sessionCookie, guestCookie)
                .header("Content-Type", "application/json");
        return httpClient.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private CsrfTicket csrfTicket(URI baseUri) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", null, null);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("X-CSRF-TOKEN", body.get("headerName").asString());
        assertFalse(body.get("token").asString().isBlank());
        return new CsrfTicket(cookieValue(response, SESSION_COOKIE), body.get("token").asString());
    }

    private HttpResponse<String> get(URI baseUri, String path, String sessionCookie, String guestCookie)
            throws Exception {
        return httpClient.send(request(baseUri, path, sessionCookie, guestCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(
            URI baseUri, String path, String sessionCookie, String guestCookie) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10));
        StringBuilder cookies = new StringBuilder();
        if (sessionCookie != null) {
            cookies.append(SESSION_COOKIE).append('=').append(sessionCookie);
        }
        if (guestCookie != null) {
            if (!cookies.isEmpty()) {
                cookies.append("; ");
            }
            cookies.append(GUEST_COOKIE).append('=').append(guestCookie);
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookies.toString());
        }
        return request;
    }

    private void assertExpiryCapped(JsonNode response, Instant guestExpiry) {
        Instant signedExpiry = Instant.parse(response.get("downloadUrlExpiresAt").asString());
        assertFalse(signedExpiry.isAfter(guestExpiry), "The signed URL must not outlive guest data.");
        assertTrue(!signedExpiry.isBefore(guestExpiry.minusSeconds(2)),
                "With a three-minute guest lifetime remaining, the five-minute URL TTL must be capped near expiry.");
    }

    private void assertAnalysisNotFound(HttpResponse<String> response, String scenario) {
        assertEquals(404, response.statusCode(), scenario);
        assertEquals("application/json", mediaType(response), scenario);
        assertEquals(ANALYSIS_NOT_FOUND, response.body(), scenario);
    }

    private GuestSession guestSession(UUID id) {
        return jdbcTemplate.queryForObject("""
                        SELECT id, expires_at, last_seen_at, created_at
                        FROM invoward.guest_sessions WHERE id = ?
                        """,
                (result, row) -> new GuestSession(
                        result.getObject("id", UUID.class),
                        result.getTimestamp("expires_at").toInstant(),
                        result.getTimestamp("last_seen_at").toInstant(),
                        result.getTimestamp("created_at").toInstant()),
                id);
    }

    private Instant guestLastSeen(UUID guestSessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_seen_at FROM invoward.guest_sessions WHERE id = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), guestSessionId);
    }

    private Instant persistedReportExpiry(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.generated_reports WHERE analysis_id = ?",
                (result, row) -> result.getTimestamp(1).toInstant(), analysisId);
    }

    private long guestSessionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoward.guest_sessions", Long.class);
    }

    private static boolean hasGuestCookieSet(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .anyMatch(value -> value.startsWith(GUEST_COOKIE + "="));
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

    private static ExtractionReview confirmedExtractionReview() {
        LocalDate date = LocalDate.of(2026, 10, 1);
        return new ExtractionReview(
                reviewedDocument(DocumentRole.REFERENCE, DocumentType.QUOTE, "GUEST-REFERENCE", date),
                reviewedDocument(DocumentRole.INVOICE, DocumentType.INVOICE, "GUEST-INVOICE", date));
    }

    private static ExtractionReview.ReviewedDocument reviewedDocument(
            DocumentRole role, DocumentType type, String number, LocalDate date) {
        ExtractedDocument extraction = new ExtractedDocument(
                ExtractionStatus.CONFIRMED, ExtractionSource.AI, Instant.parse("2026-10-01T12:00:00Z"),
                "Guest Reporting Vendor", number, date, "USD", null, null, null, null, List.of());
        return new ExtractionReview.ReviewedDocument(
                UUID.randomUUID(), UUID.randomUUID(), role, type, 0, List.of(), extraction);
    }

    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }

    private record CsrfTicket(String sessionCookie, String token) {
    }

    private record ReportFixture(UUID analysisId, AnalysisOwner owner) {
    }

    private record DeniedOwner(String label, UUID analysisId, String cookie) {
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
