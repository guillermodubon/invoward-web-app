package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/** Verifies registered-user ownership over the real matching HTTP flow and PostgreSQL state. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class LineItemMatchesRegisteredOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String RAW_PASSWORD = "registered ownership integration password";
    private static final String ANALYSIS_NOT_FOUND =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";

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
    void registeredOwnerCanReadUpdateAndConfirmWhileOtherUserGetsGenericNotFound() throws Exception {
        UserFixture userA = insertActiveVerifiedUser("match-owner-a");
        UserFixture userB = insertActiveVerifiedUser("match-owner-b");
        MatchingFixture registeredFixture = createReviewFixture(new RegisteredUserOwner(userA.id()));
        GuestSession guestSession = guestSessionService.create();
        GuestSessionOwner guestOwner = new GuestSessionOwner(guestSession.id(), guestSession.expiresAt());
        MatchingFixture guestFixture = createCompleteFixture(guestOwner);
        assertTrue(guestSessionService.findActive(guestSession.id()).isPresent());
        Instant guestLastSeenBefore = guestLastSeen(guestSession.id());

        AuthenticatedSession sessionA = login(applicationUri(), userA);
        AuthenticatedSession sessionB = login(applicationUri(), userB);
        URI baseUri = applicationUri();

        HttpResponse<String> ownerGet = get(
                baseUri, matchesPath(registeredFixture.analysisId()), sessionA.cookie(), guestSession.id());
        assertEquals(200, ownerGet.statusCode());
        JsonNode initialMatchSet = objectMapper.readTree(ownerGet.body());
        assertEquals(registeredFixture.analysisId().toString(),
                initialMatchSet.get("analysisId").asString());
        assertEquals("AWAITING_MATCH_REVIEW", initialMatchSet.get("analysisStatus").asString());
        assertEquals("NEEDS_REVIEW",
                findMatch(initialMatchSet, registeredFixture.matchId()).get("status").asString());
        assertFalse(ownerGet.body().contains(guestSession.id().toString()));

        HttpResponse<String> ownerPatch = patchJson(
                baseUri,
                matchPath(registeredFixture.analysisId(), registeredFixture.matchId()),
                sessionA,
                guestSession.id(),
                """
                        {"expectedVersion":0,"action":"CONFIRM"}
                        """);
        assertEquals(200, ownerPatch.statusCode());
        JsonNode updatedMatchSet = objectMapper.readTree(ownerPatch.body());
        JsonNode confirmedMatch = findMatch(updatedMatchSet, registeredFixture.matchId());
        assertEquals("MATCHED", confirmedMatch.get("status").asString());
        assertEquals("FUZZY", confirmedMatch.get("method").asString());
        assertEquals(1, confirmedMatch.get("version").asInt());

        HttpResponse<String> ownerConfirm = postWithoutBody(
                baseUri,
                matchesPath(registeredFixture.analysisId()) + "/confirm",
                sessionA,
                guestSession.id());
        assertEquals(204, ownerConfirm.statusCode());
        assertEquals("RECONCILING", jdbcTemplate.queryForObject(
                "SELECT status FROM invoward.analyses WHERE id = ?",
                String.class,
                registeredFixture.analysisId()));
        assertEquals("RECONCILING", jdbcTemplate.queryForObject(
                "SELECT current_stage FROM invoward.analysis_jobs WHERE analysis_id = ?",
                String.class,
                registeredFixture.analysisId()));
        assertEquals(guestLastSeenBefore, guestLastSeen(guestSession.id()),
                "An authenticated request must not touch the accompanying guest session.");

        clearInvocations(
                documentRepository,
                extractedDocumentRepository,
                lineItemMatchRepository);
        // This guest cookie is valid and owns a readable Analysis, but authenticated identity wins.
        HttpResponse<String> authenticatedAgainstGuest = get(
                baseUri, matchesPath(guestFixture.analysisId()), sessionA.cookie(), guestSession.id());
        assertAnalysisNotFound(authenticatedAgainstGuest);

        HttpResponse<String> otherUserGet = get(
                baseUri, matchesPath(registeredFixture.analysisId()), sessionB.cookie(), null);
        HttpResponse<String> otherUserPatch = patchJson(
                baseUri,
                matchPath(registeredFixture.analysisId(), registeredFixture.matchId()),
                sessionB,
                null,
                """
                        {"expectedVersion":1,"action":"CONFIRM"}
                        """);
        HttpResponse<String> otherUserConfirm = postWithoutBody(
                baseUri,
                matchesPath(registeredFixture.analysisId()) + "/confirm",
                sessionB,
                null);

        assertEquals(ANALYSIS_NOT_FOUND, assertAnalysisNotFound(otherUserGet));
        assertEquals(ANALYSIS_NOT_FOUND, assertAnalysisNotFound(otherUserPatch));
        assertEquals(ANALYSIS_NOT_FOUND, assertAnalysisNotFound(otherUserConfirm));
        verifyNoInteractions(
                documentRepository,
                extractedDocumentRepository,
                lineItemMatchRepository,
                ambiguousLineMatcher);
    }

    private MatchingFixture createReviewFixture(AnalysisOwner owner) {
        Analysis analysis = createAnalysisAwaitingMatchReview(owner);
        Instant createdAt = analysis.updatedAt();
        LineIds lines = createConfirmedDocumentsAndLines(analysis, createdAt);
        UUID matchId = UUID.randomUUID();
        lineItemMatchRepository.create(new LineItemMatch(
                matchId, analysis.id(), lines.referenceLineId(), lines.invoiceLineId(),
                LineMatchStatus.NEEDS_REVIEW, LineMatchMethod.FUZZY,
                new BigDecimal("0.8123"), 0, null, createdAt, createdAt));
        return new MatchingFixture(analysis.id(), owner, matchId);
    }

    private MatchingFixture createCompleteFixture(AnalysisOwner owner) {
        Analysis analysis = createAnalysisAwaitingMatchReview(owner);
        Instant createdAt = analysis.updatedAt();
        LineIds lines = createConfirmedDocumentsAndLines(analysis, createdAt);
        UUID matchId = UUID.randomUUID();
        lineItemMatchRepository.create(new LineItemMatch(
                matchId, analysis.id(), lines.referenceLineId(), lines.invoiceLineId(),
                LineMatchStatus.MATCHED, LineMatchMethod.SKU, BigDecimal.ONE,
                0, createdAt, createdAt, createdAt));
        return new MatchingFixture(analysis.id(), owner, matchId);
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
                invoice, analysis.owner() instanceof GuestSessionOwner ? "REF-1" : "INV-1",
                "Invoice widget", createdAt.plusSeconds(1));
        return new LineIds(referenceLineId, invoiceLineId);
    }

    private Document createDocument(
            UUID analysisId, DocumentRole role, DocumentType type, Instant expiresAt, Instant createdAt) {
        Document document = Document.createUploaded(
                        UUID.randomUUID(), analysisId, role, role + ".pdf", "application/pdf", 1024,
                        1, sha256(), "registered-ownership/" + UUID.randomUUID(), expiresAt, createdAt)
                .withDetectedType(type)
                .withConfirmedType(type);
        return documentRepository.create(document);
    }

    private UUID createConfirmedExtraction(
            Document document, String itemCode, String description, Instant extractedAt) {
        List<ExtractedLineItem> lines = List.of(new ExtractedLineItem(
                0, itemCode, description,
                new BigDecimal("1.0000"), "each", new BigDecimal("10.0000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.0000"),
                1, null, null));
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Test Vendor", null, null, "USD",
                null, null, null, null, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "registered-ownership-test-v1", "test-model", 1, extractedAt);
        Instant confirmedAt = extractedAt.plusSeconds(1);
        PersistedExtraction confirmed = extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return confirmed.lineItemIds().getFirst();
    }

    private UserFixture insertActiveVerifiedUser(String emailPrefix) {
        UUID id = UUID.randomUUID();
        String email = emailPrefix + "-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Matching Ownership User', ?, ?, true, 'ACTIVE')
                """, id, email, passwordEncoder.encode(RAW_PASSWORD));
        return new UserFixture(id, email);
    }

    private AuthenticatedSession login(URI baseUri, UserFixture user) throws Exception {
        CsrfTicket preAuthentication = csrfTicket(baseUri, null);
        HttpResponse<String> response = postJson(
                baseUri,
                "/api/auth/login",
                preAuthentication.sessionCookie(),
                preAuthentication.headerName(),
                preAuthentication.token(),
                objectMapper.writeValueAsString(Map.of("email", user.email(), "password", RAW_PASSWORD)),
                null);
        assertEquals(200, response.statusCode());
        String authenticatedCookie = cookieValue(response, SESSION_COOKIE);
        assertNotEquals(preAuthentication.sessionCookie(), authenticatedCookie);
        CsrfTicket authenticatedCsrf = csrfTicket(baseUri, authenticatedCookie);
        return new AuthenticatedSession(
                authenticatedCsrf.sessionCookie(), authenticatedCsrf.headerName(), authenticatedCsrf.token());
    }

    private CsrfTicket csrfTicket(URI baseUri, String sessionCookie) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", sessionCookie, null);
        assertEquals(200, response.statusCode());
        JsonNode csrf = objectMapper.readTree(response.body());
        String updatedCookie = updatedCookieValue(response, SESSION_COOKIE, sessionCookie);
        assertNotNull(updatedCookie);
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        assertFalse(csrf.get("token").asString().isBlank());
        return new CsrfTicket(updatedCookie, csrf.get("headerName").asString(), csrf.get("token").asString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, String sessionCookie, UUID guestSessionId) throws Exception {
        HttpRequest.Builder request = request(baseUri, path, sessionCookie, guestSessionId).GET();
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(
            URI baseUri,
            String path,
            String sessionCookie,
            String csrfHeader,
            String csrfToken,
            String body,
            UUID guestSessionId) throws Exception {
        HttpRequest.Builder request = request(baseUri, path, sessionCookie, guestSessionId)
                .header("Content-Type", "application/json");
        if (csrfHeader != null && csrfToken != null) {
            request.header(csrfHeader, csrfToken);
        }
        return httpClient.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(
            URI baseUri,
            String path,
            AuthenticatedSession session,
            UUID guestSessionId,
            String body) throws Exception {
        return httpClient.send(
                request(baseUri, path, session.cookie(), guestSessionId)
                        .header("Content-Type", "application/json")
                        .header(session.csrfHeader(), session.csrfToken())
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutBody(
            URI baseUri, String path, AuthenticatedSession session, UUID guestSessionId) throws Exception {
        return httpClient.send(
                request(baseUri, path, session.cookie(), guestSessionId)
                        .header(session.csrfHeader(), session.csrfToken())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(
            URI baseUri, String path, String sessionCookie, UUID guestSessionId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10));
        List<String> cookies = new ArrayList<>();
        if (sessionCookie != null) {
            cookies.add(SESSION_COOKIE + "=" + sessionCookie);
        }
        if (guestSessionId != null) {
            cookies.add(GUEST_COOKIE + "=" + guestSessionId);
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", String.join("; ", cookies));
        }
        return request;
    }

    private String assertAnalysisNotFound(HttpResponse<String> response) throws Exception {
        assertEquals(404, response.statusCode());
        assertEquals("application/json", response.headers()
                .firstValue("Content-Type").orElseThrow().split(";", 2)[0]);
        assertEquals(ANALYSIS_NOT_FOUND, response.body());
        return response.body();
    }

    private Instant guestLastSeen(UUID guestSessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_seen_at FROM invoward.guest_sessions WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp("last_seen_at").toInstant(),
                guestSessionId);
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private static JsonNode findMatch(JsonNode matchSet, UUID matchId) {
        for (JsonNode match : matchSet.get("matches")) {
            if (matchId.toString().equals(match.get("id").asString())) {
                return match;
            }
        }
        throw new AssertionError("Expected persisted match was absent from the response.");
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
                .findFirst()
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new AssertionError("Expected a " + cookieName + " cookie."));
    }

    private static String updatedCookieValue(
            HttpResponse<?> response, String cookieName, String fallback) {
        String prefix = cookieName + "=";
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .findFirst()
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .orElse(fallback);
    }

    private static String sha256() {
        String value = UUID.randomUUID().toString().replace("-", "");
        return value + value;
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    private record UserFixture(UUID id, String email) {
    }

    private record CsrfTicket(String sessionCookie, String headerName, String token) {
    }

    private record AuthenticatedSession(String cookie, String csrfHeader, String csrfToken) {
    }

    private record MatchingFixture(UUID analysisId, AnalysisOwner owner, UUID matchId) {
    }

    private record LineIds(UUID referenceLineId, UUID invoiceLineId) {
    }
}
