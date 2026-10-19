package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
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
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies guest reconciliation ownership, fixed expiry, and duplicate privacy over HTTP. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class ReconciliationGuestOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String ANALYSIS_NOT_FOUND =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";
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
    private GuestSessionService guestSessionService;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void owningGuestCanUseAllEndpointsAndOtherOrInvalidGuestCookiesRemainNotFound() throws Exception {
        GuestSession ownerGuest = guestSessionService.create();
        ReconciliationFixture fixture = createReconcilableAnalysis(ownerGuest);
        GuestSession foreignGuest = guestSessionService.create();
        UUID sameOwnerDuplicate = createDuplicateCandidate(
                ownerGuest.id(), fixture.invoiceNumber(), fixture.supplierKey());
        UUID otherGuestDuplicate = createDuplicateCandidate(
                foreignGuest.id(), fixture.invoiceNumber(), fixture.supplierKey());
        GuestSession expiredGuest = insertExpiredGuestSession();
        jdbcTemplate.update(
                "UPDATE invoward.guest_sessions SET last_seen_at = created_at WHERE id = ?", ownerGuest.id());

        GuestRow ownerBefore = guestRow(ownerGuest.id());
        GuestRow foreignBefore = guestRow(foreignGuest.id());
        GuestRow expiredBefore = guestRow(expiredGuest.id());
        long guestCountBeforeRequests = guestSessionCount();
        CsrfTicket csrf = csrfTicket();
        URI baseUri = applicationUri();

        HttpResponse<String> postResult = postWithoutBody(
                baseUri, reconcilePath(fixture.analysisId()), csrf, ownerGuest.id().toString());
        assertEquals(202, postResult.statusCode(), postResult.body());
        assertEquals("/api/analyses/" + fixture.analysisId() + "/status",
                postResult.headers().firstValue("Location").orElseThrow());
        JsonNode commandStatus = objectMapper.readTree(postResult.body());
        assertEquals(fixture.analysisId().toString(), commandStatus.get("analysisId").asString());
        assertEquals("RECONCILING", commandStatus.get("status").asString());
        assertTrue(List.of("QUEUED", "RUNNING").contains(commandStatus.get("jobStatus").asString()));
        assertFalse(postResult.body().contains(ownerGuest.id().toString()),
                "The guest-session identifier must not appear in the command response.");

        JsonNode postBody = awaitReconciliation(
                baseUri, fixture.analysisId(), csrf.sessionCookie(), ownerGuest.id().toString());
        assertEquals("GENERATING_REPORT", postBody.get("analysisStatus").asString());
        assertTrue(postBody.get("duplicateInvoice").get("possibleDuplicate").asBoolean());
        List<String> duplicateIds = postBody.get("duplicateInvoice").get("candidates").valueStream()
                .map(candidate -> candidate.get("analysisId").asString())
                .toList();
        assertEquals(List.of(sameOwnerDuplicate.toString()), duplicateIds);
        assertFalse(objectMapper.writeValueAsString(postBody).contains(otherGuestDuplicate.toString()),
                "A candidate belonging to another GuestSession must not be disclosed.");
        assertFalse(objectMapper.writeValueAsString(postBody).contains(ownerGuest.id().toString()),
                "The guest-session identifier must not appear in the API response.");

        JsonNode discrepancy = findDiscrepancy(postBody, "UNIT_PRICE_MISMATCH");
        UUID discrepancyId = UUID.fromString(discrepancy.get("id").asString());
        assertEquals("OPEN", discrepancy.get("resolutionStatus").asString());

        HttpResponse<String> ownerRead = get(
                baseUri, reconciliationPath(fixture.analysisId()), csrf.sessionCookie(), ownerGuest.id().toString());
        assertEquals(200, ownerRead.statusCode(), ownerRead.body());
        assertEquals("REVIEW_REQUIRED", objectMapper.readTree(ownerRead.body())
                .get("reconciliationStatus").asString());

        HttpResponse<String> ownerUpdate = patchJson(
                baseUri,
                discrepancyPath(fixture.analysisId(), discrepancyId),
                csrf,
                ownerGuest.id().toString(),
                """
                        {"expectedVersion":0,"resolutionStatus":"ACCEPTED",
                        "resolutionReason":"APPROVED_CHANGE","resolutionNote":null}
                        """);
        assertEquals(200, ownerUpdate.statusCode(), ownerUpdate.body());
        assertEquals("ACCEPTED", objectMapper.readTree(ownerUpdate.body())
                .get("resolutionStatus").asString());

        HttpResponse<String> ownerEvidence = get(
                baseUri,
                discrepancyPath(fixture.analysisId(), discrepancyId) + "/evidence",
                csrf.sessionCookie(),
                ownerGuest.id().toString());
        assertEquals(200, ownerEvidence.statusCode(), ownerEvidence.body());
        JsonNode evidence = objectMapper.readTree(ownerEvidence.body());
        assertEquals(2, evidence.size());
        assertEquals(List.of("INVOICE", "REFERENCE"), evidence.valueStream()
                .map(item -> item.get("side").asString()).sorted().toList());
        assertTrue(ownerEvidence.body().contains("Reference guest source evidence"));
        assertTrue(ownerEvidence.body().contains("Invoice guest source evidence"));
        assertFalse(ownerEvidence.body().contains("storageKey"));

        GuestRow ownerAfterSuccess = guestRow(ownerGuest.id());
        assertTrue(ownerAfterSuccess.lastSeenAt().isAfter(ownerBefore.lastSeenAt()),
                "Successful guest operations should record lastSeen activity.");
        assertEquals(ownerBefore.expiresAt(), ownerAfterSuccess.expiresAt(),
                "Guest activity must not extend the fixed session expiry.");
        assertEquals(ownerAfterSuccess.expiresAt(), analysisExpiry(fixture.analysisId()),
                "Analysis expiry must remain tied to its GuestSession expiry.");
        assertEquals(guestCountBeforeRequests, guestSessionCount(),
                "Nested reconciliation endpoints must not create GuestSessions.");

        GuestRow ownerBeforeRejectedRequests = guestRow(ownerGuest.id());
        List<GuestCookieCase> invalidCookies = new ArrayList<>(List.of(
                new GuestCookieCase("Guest B", foreignGuest.id().toString()),
                new GuestCookieCase("malformed", "not-a-uuid"),
                new GuestCookieCase("unknown", UUID.randomUUID().toString()),
                new GuestCookieCase("expired", expiredGuest.id().toString())));
        invalidCookies.add(new GuestCookieCase("missing", null));

        for (GuestCookieCase cookieCase : invalidCookies) {
            assertAnalysisNotFound(postWithoutBody(
                    baseUri, reconcilePath(fixture.analysisId()), csrf, cookieCase.cookie()),
                    cookieCase.label() + " POST reconcile");
            assertAnalysisNotFound(get(
                    baseUri,
                    reconciliationPath(fixture.analysisId()),
                    csrf.sessionCookie(),
                    cookieCase.cookie()),
                    cookieCase.label() + " GET reconciliation");
            assertAnalysisNotFound(patchJson(
                    baseUri,
                    discrepancyPath(fixture.analysisId(), discrepancyId),
                    csrf,
                    cookieCase.cookie(),
                    """
                            {"expectedVersion":1,"resolutionStatus":"RESOLVED",
                            "resolutionReason":"APPROVED_CHANGE","resolutionNote":null}
                            """),
                    cookieCase.label() + " PATCH discrepancy");
            assertAnalysisNotFound(get(
                    baseUri,
                    discrepancyPath(fixture.analysisId(), discrepancyId) + "/evidence",
                    csrf.sessionCookie(),
                    cookieCase.cookie()),
                    cookieCase.label() + " GET evidence");
        }

        assertEquals(guestCountBeforeRequests, guestSessionCount(),
                "Rejected nested requests must not create GuestSessions.");
        assertEquals(ownerBeforeRejectedRequests, guestRow(ownerGuest.id()),
                "Rejected requests must not touch the owning GuestSession.");
        assertEquals(foreignBefore, guestRow(foreignGuest.id()),
                "Rejected requests must not touch Guest B.");
        assertEquals(expiredBefore, guestRow(expiredGuest.id()),
                "Rejected requests must not touch an expired GuestSession.");
    }

    private ReconciliationFixture createReconcilableAnalysis(GuestSession guest) {
        GuestSessionOwner owner = new GuestSessionOwner(guest.id(), guest.expiresAt());
        Instant start = clock.instant().minusSeconds(120);
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), start));
        AnalysisJob job = analysisJobRepository.create(
                AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, start));

        Instant uploadedAt = start.plusSeconds(1);
        analysis = analysisRepository.update(analysis.transitionToUploading(uploadedAt));
        job = analysisJobRepository.update(job.awaitMoreUploads(uploadedAt));
        Instant classifyingAt = start.plusSeconds(2);
        analysis = analysisRepository.update(analysis.markClassifying(classifyingAt));
        job = analysisJobRepository.update(job.waitForUserAtClassification(classifyingAt));
        Instant extractionReviewAt = start.plusSeconds(3);
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(extractionReviewAt));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(extractionReviewAt));

        Instant documentCreatedAt = start.plusSeconds(4);
        Document reference = createDocument(
                analysisId, DocumentRole.REFERENCE, DocumentType.QUOTE, analysis.expiresAt(), documentCreatedAt);
        Document invoice = createDocument(
                analysisId, DocumentRole.INVOICE, DocumentType.INVOICE, analysis.expiresAt(), documentCreatedAt);
        Instant extractionAt = start.plusSeconds(5);
        UUID referenceLineId = createConfirmedExtraction(
                reference, "QUOTE-ITEM-1", "Reference guest", new BigDecimal("10.0000"), extractionAt);
        UUID invoiceLineId = createConfirmedExtraction(
                invoice, "QUOTE-ITEM-1", "Invoice guest", new BigDecimal("12.0000"), extractionAt);

        String invoiceNumber = "INV-GUEST-" + UUID.randomUUID();
        String supplierName = "Guest Ownership Supplier";
        Instant extractionConfirmedAt = start.plusSeconds(7);
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "QUOTE-GUEST-1", invoiceNumber, supplierName, "USD",
                BigDecimal.TEN, BigDecimal.TEN, extractionConfirmedAt));
        job = analysisJobRepository.update(job.waitForMatching(extractionConfirmedAt));
        Instant matchReviewAt = start.plusSeconds(8);
        analysis = analysisRepository.update(analysis.awaitMatchReview(matchReviewAt));
        analysisJobRepository.update(job.waitForMatchReview(matchReviewAt));

        Instant matchCreatedAt = start.plusSeconds(9);
        lineItemMatchRepository.create(new LineItemMatch(
                UUID.randomUUID(), analysisId, referenceLineId, invoiceLineId,
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE,
                0, matchCreatedAt, matchCreatedAt, matchCreatedAt));
        Instant reconciliationAt = start.plusSeconds(10);
        analysisRepository.update(analysis.beginReconciliation(reconciliationAt));
        analysisJobRepository.update(job.waitForReconciliation(reconciliationAt));

        return new ReconciliationFixture(analysisId, invoiceNumber, analysis.supplierKey());
    }

    private Document createDocument(
            UUID analysisId, DocumentRole role, DocumentType type, Instant expiresAt, Instant createdAt) {
        return documentRepository.create(Document.createUploaded(
                        UUID.randomUUID(), analysisId, role, role + ".pdf", "application/pdf",
                        1024, 1, sha256(), "guest-reconciliation/" + UUID.randomUUID(), expiresAt, createdAt)
                .withDetectedType(type)
                .withConfirmedType(type));
    }

    private UUID createConfirmedExtraction(
            Document document, String itemCode, String description, BigDecimal unitPrice, Instant extractedAt) {
        List<ExtractedLineItem> lines = List.of(new ExtractedLineItem(
                0, itemCode, description, BigDecimal.ONE, "each", unitPrice,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                1, description + " source evidence", null));
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Guest Ownership Supplier", null, null, "USD",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "guest-reconciliation-test-v1", "test-model", 1, extractedAt);
        Instant confirmedAt = extractedAt.plusSeconds(1);
        extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return created.lineItemIds().getFirst();
    }

    private UUID createDuplicateCandidate(UUID guestSessionId, String invoiceNumber, String supplierKey) {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) connection -> {
            UUID candidateAnalysisId = DatabaseFixtures.insertAnalysis(
                    connection, null, guestSessionId, true);
            try (var update = connection.prepareStatement("""
                    UPDATE invoward.analyses
                    SET invoice_number = ?, supplier_key = ?, invoiced_total = ?
                    WHERE id = ?
                    """)) {
                update.setString(1, invoiceNumber);
                update.setString(2, supplierKey);
                update.setBigDecimal(3, BigDecimal.TEN);
                update.setObject(4, candidateAnalysisId);
                update.executeUpdate();
            }
            DatabaseFixtures.insertDocument(connection, candidateAnalysisId, "INVOICE", "INVOICE",
                    "guest-duplicate-fixture/" + UUID.randomUUID(), sha256(), 1024, 1);
            return candidateAnalysisId;
        });
    }

    private GuestSession insertExpiredGuestSession() {
        UUID id = UUID.randomUUID();
        Instant createdAt = clock.instant().minus(Duration.ofHours(48));
        Instant lastSeenAt = createdAt.plus(Duration.ofHours(1));
        Instant expiresAt = createdAt.plus(Duration.ofHours(24));
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, id, Timestamp.from(expiresAt), Timestamp.from(lastSeenAt), Timestamp.from(createdAt));
        return new GuestSession(id, expiresAt, lastSeenAt, createdAt);
    }

    private CsrfTicket csrfTicket() throws Exception {
        HttpResponse<String> response = get(applicationUri(), "/api/auth/csrf", null, null);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode csrf = objectMapper.readTree(response.body());
        String sessionCookie = cookieValue(response, SESSION_COOKIE);
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        assertFalse(csrf.get("token").asString().isBlank());
        return new CsrfTicket(sessionCookie, csrf.get("token").asString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, String sessionCookie, String guestCookie) throws Exception {
        return httpClient.send(request(baseUri, path, sessionCookie, guestCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode awaitReconciliation(
            URI baseUri, UUID analysisId, String sessionCookie, String guestSessionId) throws Exception {
        long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        do {
            HttpResponse<String> statusResponse = get(
                    baseUri, "/api/analyses/" + analysisId + "/status", sessionCookie, guestSessionId);
            assertEquals(200, statusResponse.statusCode(), statusResponse.body());
            if (objectMapper.readTree(statusResponse.body()).get("status").asString().equals("RECONCILING")) {
                Thread.sleep(25);
                continue;
            }
            HttpResponse<String> response = get(
                    baseUri, reconciliationPath(analysisId), sessionCookie, guestSessionId);
            assertEquals(200, response.statusCode(), response.body());
            return objectMapper.readTree(response.body());
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Persisted reconciliation did not become available before timeout.");
    }

    private HttpResponse<String> postWithoutBody(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie) throws Exception {
        return httpClient.send(request(baseUri, path, csrf.sessionCookie(), guestCookie)
                        .header("X-CSRF-TOKEN", csrf.token())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie, String body) throws Exception {
        return httpClient.send(request(baseUri, path, csrf.sessionCookie(), guestCookie)
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf.token())
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(
            URI baseUri, String path, String sessionCookie, String guestCookie) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(REQUEST_TIMEOUT);
        List<String> cookies = new ArrayList<>();
        if (sessionCookie != null) {
            cookies.add(SESSION_COOKIE + "=" + sessionCookie);
        }
        if (guestCookie != null) {
            cookies.add(GUEST_COOKIE + "=" + guestCookie);
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", String.join("; ", cookies));
        }
        return request;
    }

    private void assertAnalysisNotFound(HttpResponse<String> response, String scenario) {
        assertEquals(404, response.statusCode(), scenario + " status: " + response.body());
        assertEquals("application/json", response.headers()
                .firstValue("Content-Type").orElseThrow().split(";", 2)[0], scenario);
        assertEquals(ANALYSIS_NOT_FOUND, response.body(), scenario);
    }

    private JsonNode findDiscrepancy(JsonNode reconciliation, String type) {
        for (JsonNode discrepancy : reconciliation.get("discrepancies")) {
            if (type.equals(discrepancy.get("type").asString())) {
                return discrepancy;
            }
        }
        throw new AssertionError("Expected persisted discrepancy was absent from the response.");
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

    private long guestSessionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoward.guest_sessions", Long.class);
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private static String reconcilePath(UUID analysisId) {
        return "/api/analyses/" + analysisId + "/reconcile";
    }

    private static String reconciliationPath(UUID analysisId) {
        return "/api/analyses/" + analysisId + "/reconciliation";
    }

    private static String discrepancyPath(UUID analysisId, UUID discrepancyId) {
        return "/api/analyses/" + analysisId + "/discrepancies/" + discrepancyId;
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

    private record GuestCookieCase(String label, String cookie) {
    }

    private record GuestRow(Instant createdAt, Instant lastSeenAt, Instant expiresAt) {
    }

    private record ReconciliationFixture(UUID analysisId, String invoiceNumber, String supplierKey) {
    }
}
