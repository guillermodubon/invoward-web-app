package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
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
import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/** Verifies guest ownership and fixed guest-session expiry over the real matching HTTP API. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class LineItemMatchesGuestOwnershipIntegrationIT {

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
    private GuestSessionService guestSessionService;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @MockitoBean
    private AmbiguousLineMatcher ambiguousLineMatcher;

    @MockitoSpyBean
    private DocumentRepository documentRepository;

    @MockitoSpyBean
    private ExtractedDocumentRepository extractedDocumentRepository;

    @MockitoSpyBean
    private LineItemMatchRepository lineItemMatchRepository;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void owningGuestCanReadUpdateAndConfirmWithoutSlidingFixedExpiry() throws Exception {
        GuestSession guest = guestSessionService.create();
        MatchingFixture fixture = createReviewFixture(
                new GuestSessionOwner(guest.id(), guest.expiresAt()));
        GuestRow createdGuest = guestRow(guest.id());
        jdbcTemplate.update("UPDATE invoward.guest_sessions SET last_seen_at = ? WHERE id = ?",
                Timestamp.from(createdGuest.createdAt()), guest.id());
        GuestRow beforeRequests = guestRow(guest.id());
        long sessionsBeforeRequests = guestSessionCount();
        CsrfTicket csrf = csrfTicket();
        URI baseUri = applicationUri();

        HttpResponse<String> getResponse = get(
                baseUri, matchesPath(fixture.analysisId()), csrf.sessionCookie(), guest.id().toString());
        assertEquals(200, getResponse.statusCode(), getResponse.body());
        JsonNode matchSet = objectMapper.readTree(getResponse.body());
        assertEquals(fixture.analysisId().toString(), matchSet.get("analysisId").asString());
        assertEquals("AWAITING_MATCH_REVIEW", matchSet.get("analysisStatus").asString());
        assertEquals("NEEDS_REVIEW", matchSet.get("matches").get(0).get("status").asString());
        assertFalse(getResponse.body().contains(guest.id().toString()));
        assertGuestActivityAndFixedExpiry(guest, fixture, beforeRequests, true);

        HttpResponse<String> patchResponse = patchConfirm(
                baseUri,
                matchPath(fixture.analysisId(), fixture.matchId()),
                csrf,
                guest.id().toString());
        assertEquals(200, patchResponse.statusCode(), patchResponse.body());
        JsonNode patchedMatch = objectMapper.readTree(patchResponse.body()).get("matches").get(0);
        assertEquals("MATCHED", patchedMatch.get("status").asString());
        assertEquals("FUZZY", patchedMatch.get("method").asString());
        assertEquals(1, patchedMatch.get("version").asInt());
        assertGuestActivityAndFixedExpiry(guest, fixture, beforeRequests, false);

        HttpResponse<String> confirmResponse = postConfirm(
                baseUri,
                matchesPath(fixture.analysisId()) + "/confirm",
                csrf,
                guest.id().toString());
        assertEquals(204, confirmResponse.statusCode(), confirmResponse.body());
        assertEquals("RECONCILING", jdbcTemplate.queryForObject(
                "SELECT status FROM invoward.analyses WHERE id = ?",
                String.class,
                fixture.analysisId()));
        assertEquals("RECONCILING", jdbcTemplate.queryForObject(
                "SELECT current_stage FROM invoward.analysis_jobs WHERE analysis_id = ?",
                String.class,
                fixture.analysisId()));
        assertGuestActivityAndFixedExpiry(guest, fixture, beforeRequests, false);
        assertEquals(sessionsBeforeRequests, guestSessionCount(),
                "Nested matching routes must reuse the owner and never create a GuestSession.");
    }

    @Test
    void foreignMissingMalformedUnknownAndExpiredGuestCookiesReturnSameNotFoundWithoutCreation()
            throws Exception {
        GuestSession owner = guestSessionService.create();
        MatchingFixture fixture = createReviewFixture(
                new GuestSessionOwner(owner.id(), owner.expiresAt()));
        GuestSession foreignGuest = guestSessionService.create();
        GuestSession expiredGuest = insertExpiredGuestSession();
        GuestRow ownerBefore = guestRow(owner.id());
        GuestRow foreignBefore = guestRow(foreignGuest.id());
        GuestRow expiredBefore = guestRow(expiredGuest.id());
        String unknownGuestId = UUID.randomUUID().toString();
        List<GuestCookieCase> invalidCookies = new ArrayList<>(List.of(
                new GuestCookieCase("another guest", foreignGuest.id().toString()),
                new GuestCookieCase("malformed", "not-a-uuid"),
                new GuestCookieCase("unknown", unknownGuestId),
                new GuestCookieCase("expired", expiredGuest.id().toString())));
        invalidCookies.add(new GuestCookieCase("missing", null));
        long sessionsBeforeRequests = guestSessionCount();
        CsrfTicket csrf = csrfTicket();
        URI baseUri = applicationUri();

        clearInvocations(documentRepository, extractedDocumentRepository, lineItemMatchRepository);
        for (GuestCookieCase cookieCase : invalidCookies) {
            assertAnalysisNotFound(get(
                    baseUri, matchesPath(fixture.analysisId()), csrf.sessionCookie(), cookieCase.cookie()),
                    cookieCase.label() + " guest GET");
            assertAnalysisNotFound(patchConfirm(
                    baseUri,
                    matchPath(fixture.analysisId(), fixture.matchId()),
                    csrf,
                    cookieCase.cookie()),
                    cookieCase.label() + " guest PATCH");
            assertAnalysisNotFound(postConfirm(
                    baseUri,
                    matchesPath(fixture.analysisId()) + "/confirm",
                    csrf,
                    cookieCase.cookie()),
                    cookieCase.label() + " guest confirmation");
        }

        assertEquals(sessionsBeforeRequests, guestSessionCount(),
                "Nested matching routes must never create a GuestSession for an invalid owner.");
        assertEquals(ownerBefore, guestRow(owner.id()), "Rejected requests must not touch the owning guest.");
        assertEquals(foreignBefore, guestRow(foreignGuest.id()), "Rejected requests must not touch Guest B.");
        assertEquals(expiredBefore, guestRow(expiredGuest.id()), "Rejected requests must not touch an expired guest.");
        verifyNoInteractions(
                documentRepository,
                extractedDocumentRepository,
                lineItemMatchRepository,
                ambiguousLineMatcher);
    }

    private void assertGuestActivityAndFixedExpiry(
            GuestSession guest, MatchingFixture fixture, GuestRow before, boolean requireActivityUpdate) {
        GuestRow after = guestRow(guest.id());
        if (requireActivityUpdate) {
            assertTrue(after.lastSeenAt().isAfter(before.lastSeenAt()),
                    "A successful guest operation should record activity.");
        }
        assertEquals(before.expiresAt(), after.expiresAt(),
                "Guest activity must not extend the fixed session expiry.");
        assertEquals(before.expiresAt(), analysisExpiry(fixture.analysisId()),
                "Analysis expiry must remain tied to the guest's fixed expiry.");
    }

    private MatchingFixture createReviewFixture(AnalysisOwner owner) {
        Analysis analysis = createAnalysisAwaitingMatchReview(owner);
        Instant createdAt = analysis.updatedAt();
        LineIds lines = createConfirmedDocumentsAndLines(analysis, createdAt);
        UUID matchId = UUID.randomUUID();
        lineItemMatchRepository.create(new LineItemMatch(
                matchId,
                analysis.id(),
                lines.referenceLineId(),
                lines.invoiceLineId(),
                LineMatchStatus.NEEDS_REVIEW,
                LineMatchMethod.FUZZY,
                new BigDecimal("0.8123"),
                0,
                null,
                createdAt,
                createdAt));
        return new MatchingFixture(analysis.id(), matchId);
    }

    private Analysis createAnalysisAwaitingMatchReview(AnalysisOwner owner) {
        Instant startedAt = clock.instant().minusSeconds(120);
        UUID analysisId = UUID.randomUUID();
        Analysis analysis = analysisRepository.create(
                Analysis.create(analysisId, owner, PriceTolerance.exactMatch(), startedAt));
        AnalysisJob job = analysisJobRepository.create(
                AnalysisJob.waitingForUser(UUID.randomUUID(), analysisId, startedAt));

        Instant uploadedAt = startedAt.plusSeconds(1);
        analysis = analysisRepository.update(analysis.transitionToUploading(uploadedAt));
        job = analysisJobRepository.update(job.awaitMoreUploads(uploadedAt));
        Instant classifyingAt = startedAt.plusSeconds(2);
        analysis = analysisRepository.update(analysis.markClassifying(classifyingAt));
        job = analysisJobRepository.update(job.waitForUserAtClassification(classifyingAt));
        Instant extractionReviewAt = startedAt.plusSeconds(3);
        analysis = analysisRepository.update(analysis.awaitExtractionConfirmation(extractionReviewAt));
        job = analysisJobRepository.update(job.waitForExtractionConfirmation(extractionReviewAt));
        Instant matchingAt = startedAt.plusSeconds(4);
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "Q-1", "I-1", "Test Vendor", "USD",
                BigDecimal.TEN, BigDecimal.TEN, matchingAt));
        job = analysisJobRepository.update(job.waitForMatching(matchingAt));
        Instant matchReviewAt = startedAt.plusSeconds(5);
        Analysis awaitingReview = analysisRepository.update(analysis.awaitMatchReview(matchReviewAt));
        analysisJobRepository.update(job.waitForMatchReview(matchReviewAt));
        return awaitingReview;
    }

    private LineIds createConfirmedDocumentsAndLines(Analysis analysis, Instant createdAt) {
        Document reference = createDocument(
                analysis.id(), DocumentRole.REFERENCE, DocumentType.QUOTE, analysis.expiresAt(), createdAt);
        Document invoice = createDocument(
                analysis.id(), DocumentRole.INVOICE, DocumentType.INVOICE, analysis.expiresAt(), createdAt);
        UUID referenceLineId = createConfirmedExtraction(
                reference, "REF-1", "Reference widget", createdAt.plusSeconds(1));
        UUID invoiceLineId = createConfirmedExtraction(
                invoice, "INV-1", "Invoice widget", createdAt.plusSeconds(1));
        return new LineIds(referenceLineId, invoiceLineId);
    }

    private Document createDocument(
            UUID analysisId, DocumentRole role, DocumentType type, Instant expiresAt, Instant createdAt) {
        Document document = Document.createUploaded(
                        UUID.randomUUID(), analysisId, role, role + ".pdf", "application/pdf", 1024,
                        1, sha256(), "guest-matching-ownership/" + UUID.randomUUID(), expiresAt, createdAt)
                .withDetectedType(type)
                .withConfirmedType(type);
        return documentRepository.create(document);
    }

    private UUID createConfirmedExtraction(
            Document document, String itemCode, String description, Instant extractedAt) {
        List<ExtractedLineItem> lines = List.of(new ExtractedLineItem(
                0,
                itemCode,
                description,
                new BigDecimal("1.0000"),
                "each",
                new BigDecimal("10.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("10.0000"),
                1,
                null,
                null));
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Test Vendor", null, null, "USD",
                null, null, null, null, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "guest-matching-ownership-test-v1", "test-model", 1, extractedAt);
        Instant confirmedAt = extractedAt.plusSeconds(1);
        PersistedExtraction confirmed = extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return confirmed.lineItemIds().getFirst();
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
        return httpClient.send(
                request(baseUri, path, sessionCookie, guestCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchConfirm(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie) throws Exception {
        HttpRequest request = jsonRequest(baseUri, path, csrf, guestCookie)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        "{\"expectedVersion\":0,\"action\":\"CONFIRM\"}"))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postConfirm(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie) throws Exception {
        HttpRequest request = request(baseUri, path, csrf.sessionCookie(), guestCookie)
                .header("X-CSRF-TOKEN", csrf.token())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder jsonRequest(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie) {
        return request(baseUri, path, csrf.sessionCookie(), guestCookie)
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrf.token());
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

    private static String matchesPath(UUID analysisId) {
        return "/api/analyses/" + analysisId + "/matches";
    }

    private static String matchPath(UUID analysisId, UUID matchId) {
        return matchesPath(analysisId) + "/" + matchId;
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

    private record MatchingFixture(UUID analysisId, UUID matchId) {
    }

    private record LineIds(UUID referenceLineId, UUID invoiceLineId) {
    }
}
