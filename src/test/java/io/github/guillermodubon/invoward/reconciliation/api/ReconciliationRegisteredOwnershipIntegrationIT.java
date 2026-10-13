package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
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
import io.github.guillermodubon.invoward.reconciliation.application.service.ConfirmLineItemMatchesService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies registered reconciliation endpoints against real sessions and PostgreSQL ownership. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class ReconciliationRegisteredOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String RAW_PASSWORD = "reconciliation ownership integration passphrase";
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
    private ConfirmLineItemMatchesService confirmLineItemMatchesService;

    @Autowired
    private GuestSessionService guestSessionService;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void registeredOwnerCanUseReconciliationEndpointsWhileOtherUserGetsGenericNotFound()
            throws Exception {
        UserFixture userA = insertActiveVerifiedUser("reconciliation-owner-a");
        UserFixture userB = insertActiveVerifiedUser("reconciliation-owner-b");
        ReconciliationFixture ownerFixture = createReconcilableAnalysis(userA.id());
        UUID otherUsersDuplicateCandidate = createDuplicateCandidate(
                userB.id(), ownerFixture.invoiceNumber(), ownerFixture.supplierKey());
        GuestSession guestSession = guestSessionService.create();
        UUID guestAnalysisId = analysisRepository.create(Analysis.create(
                UUID.randomUUID(),
                new GuestSessionOwner(guestSession.id(), guestSession.expiresAt()),
                PriceTolerance.exactMatch(),
                clock.instant())).id();
        Instant guestLastSeenBeforeRequests = guestLastSeen(guestSession.id());

        URI baseUri = applicationUri();
        AuthenticatedSession sessionA = login(baseUri, userA);
        AuthenticatedSession sessionB = login(baseUri, userB);

        HttpResponse<String> postResult = postWithoutBody(
                baseUri, reconcilePath(ownerFixture.analysisId()), sessionA, null);
        assertEquals(200, postResult.statusCode());
        assertEquals("application/json", mediaType(postResult));
        JsonNode postBody = objectMapper.readTree(postResult.body());
        assertEquals(ownerFixture.analysisId().toString(), postBody.get("analysisId").asString());
        assertEquals("GENERATING_REPORT", postBody.get("analysisStatus").asString());
        assertEquals("REVIEW_REQUIRED", postBody.get("reconciliationStatus").asString());
        assertFalse(postBody.get("duplicateInvoice").get("possibleDuplicate").asBoolean());
        assertTrue(postBody.get("duplicateInvoice").get("candidates").isEmpty(),
                "A duplicate candidate belonging to another user must not be disclosed.");
        assertFalse(postResult.body().contains(otherUsersDuplicateCandidate.toString()));

        JsonNode unitPriceDiscrepancy = findDiscrepancy(postBody, "UNIT_PRICE_MISMATCH");
        UUID discrepancyId = UUID.fromString(unitPriceDiscrepancy.get("id").asString());
        assertEquals("OPEN", unitPriceDiscrepancy.get("resolutionStatus").asString());

        HttpResponse<String> ownerRead = get(
                baseUri, reconciliationPath(ownerFixture.analysisId()), sessionA.cookie(), null);
        assertEquals(200, ownerRead.statusCode());
        JsonNode readBody = objectMapper.readTree(ownerRead.body());
        assertEquals("OPEN", findDiscrepancy(readBody, "UNIT_PRICE_MISMATCH")
                .get("resolutionStatus").asString());

        HttpResponse<String> ownerUpdate = patchJson(
                baseUri,
                discrepancyPath(ownerFixture.analysisId(), discrepancyId),
                sessionA,
                null,
                """
                        {"expectedVersion":0,"resolutionStatus":"ACCEPTED",
                        "resolutionReason":"APPROVED_CHANGE","resolutionNote":null}
                        """);
        assertEquals(200, ownerUpdate.statusCode());
        JsonNode updatedDiscrepancy = objectMapper.readTree(ownerUpdate.body());
        assertEquals(discrepancyId.toString(), updatedDiscrepancy.get("id").asString());
        assertEquals("ACCEPTED", updatedDiscrepancy.get("resolutionStatus").asString());
        assertEquals(1, updatedDiscrepancy.get("version").asInt());

        HttpResponse<String> ownerEvidence = get(
                baseUri,
                discrepancyPath(ownerFixture.analysisId(), discrepancyId) + "/evidence",
                sessionA.cookie(),
                null);
        assertEquals(200, ownerEvidence.statusCode());
        JsonNode evidence = objectMapper.readTree(ownerEvidence.body());
        assertEquals(2, evidence.size());
        assertEquals(List.of("INVOICE", "REFERENCE"), evidence.valueStream()
                .map(item -> item.get("side").asString()).sorted().toList());
        assertTrue(ownerEvidence.body().contains("Reference widget source evidence"));
        assertTrue(ownerEvidence.body().contains("Invoice widget source evidence"));
        assertFalse(ownerEvidence.body().contains("storageKey"));

        HttpResponse<String> ownReadWithGuestCookie = get(
                baseUri,
                reconciliationPath(ownerFixture.analysisId()),
                sessionA.cookie(),
                guestSession.id());
        assertEquals(200, ownReadWithGuestCookie.statusCode(),
                "Authenticated identity must take precedence over the guest cookie.");
        assertEquals(guestLastSeenBeforeRequests, guestLastSeen(guestSession.id()),
                "An authenticated request must not touch the accompanying guest session.");
        HttpResponse<String> authenticatedAgainstGuest = get(
                baseUri,
                reconciliationPath(guestAnalysisId),
                sessionA.cookie(),
                guestSession.id());
        assertAnalysisNotFound(authenticatedAgainstGuest);

        HttpResponse<String> userBPost = postWithoutBody(
                baseUri,
                reconcilePath(ownerFixture.analysisId()),
                sessionB,
                null);
        HttpResponse<String> userBGet = get(
                baseUri, reconciliationPath(ownerFixture.analysisId()), sessionB.cookie(), null);
        HttpResponse<String> userBPatch = patchJson(
                baseUri,
                discrepancyPath(ownerFixture.analysisId(), discrepancyId),
                sessionB,
                null,
                """
                        {"expectedVersion":1,"resolutionStatus":"RESOLVED",
                        "resolutionReason":"APPROVED_CHANGE","resolutionNote":null}
                        """);
        HttpResponse<String> userBEvidence = get(
                baseUri,
                discrepancyPath(ownerFixture.analysisId(), discrepancyId) + "/evidence",
                sessionB.cookie(),
                null);

        assertAnalysisNotFound(userBPost);
        assertAnalysisNotFound(userBGet);
        assertAnalysisNotFound(userBPatch);
        assertAnalysisNotFound(userBEvidence);
    }

    private ReconciliationFixture createReconcilableAnalysis(UUID userId) {
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
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
                analysisId, DocumentRole.REFERENCE, DocumentType.QUOTE, sha256(), documentCreatedAt);
        Document invoice = createDocument(
                analysisId, DocumentRole.INVOICE, DocumentType.INVOICE, sha256(), documentCreatedAt);
        Instant extractionAt = start.plusSeconds(5);
        UUID referenceLineId = createConfirmedExtraction(
                reference, "QUOTE-ITEM-1", "Reference widget", new BigDecimal("10.0000"), extractionAt);
        UUID invoiceLineId = createConfirmedExtraction(
                invoice, "QUOTE-ITEM-1", "Invoice widget", new BigDecimal("12.0000"), extractionAt);

        String invoiceNumber = "INV-OWNERSHIP-" + UUID.randomUUID();
        String supplierName = "Ownership Test Supplier";
        Instant extractionConfirmedAt = start.plusSeconds(7);
        analysis = analysisRepository.update(analysis.confirmExtraction(
                "QUOTE", "QUOTE-OWNERSHIP-1", invoiceNumber, supplierName, "USD",
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
        confirmLineItemMatchesService.confirm(analysisId, owner);

        return new ReconciliationFixture(analysisId, invoiceNumber, analysis.supplierKey());
    }

    private Document createDocument(
            UUID analysisId, DocumentRole role, DocumentType type, String hash, Instant createdAt) {
        Document document = Document.createUploaded(
                        UUID.randomUUID(), analysisId, role, role + ".pdf", "application/pdf",
                        1024, 1, hash, "registered-reconciliation/" + UUID.randomUUID(), null, createdAt)
                .withDetectedType(type)
                .withConfirmedType(type);
        return documentRepository.create(document);
    }

    private UUID createConfirmedExtraction(
            Document document, String itemCode, String description, BigDecimal unitPrice, Instant extractedAt) {
        List<ExtractedLineItem> lines = List.of(new ExtractedLineItem(
                0, itemCode, description, BigDecimal.ONE, "each", unitPrice,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN,
                1, description + " source evidence", null));
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Ownership Test Supplier", null, null, "USD",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "ownership-test-v1", "test-model", 1, extractedAt);
        Instant confirmedAt = extractedAt.plusSeconds(1);
        extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return created.lineItemIds().getFirst();
    }

    private UUID createDuplicateCandidate(UUID ownerId, String invoiceNumber, String supplierKey) {
        return jdbcTemplate.execute((ConnectionCallback<UUID>) connection -> {
            UUID candidateAnalysisId = DatabaseFixtures.insertAnalysis(connection, ownerId, null, false);
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
                    "duplicate-owner-fixture/" + UUID.randomUUID(), sha256(), 1024, 1);
            return candidateAnalysisId;
        });
    }

    private UserFixture insertActiveVerifiedUser(String emailPrefix) {
        UUID id = UUID.randomUUID();
        String email = emailPrefix + "-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Reconciliation Ownership User', ?, ?, true, 'ACTIVE')
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
        assertNotEquals(preAuthentication.sessionCookie(), authenticatedCookie,
                "Login must rotate the pre-authentication session ID.");
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
        return httpClient.send(request(baseUri, path, sessionCookie, guestSessionId).GET().build(),
                HttpResponse.BodyHandlers.ofString());
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
        return httpClient.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutBody(
            URI baseUri, String path, AuthenticatedSession session, UUID guestSessionId) throws Exception {
        return httpClient.send(request(baseUri, path, session.cookie(), guestSessionId)
                        .header(session.csrfHeader(), session.csrfToken())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(
            URI baseUri,
            String path,
            AuthenticatedSession session,
            UUID guestSessionId,
            String body) throws Exception {
        return httpClient.send(request(baseUri, path, session.cookie(), guestSessionId)
                        .header("Content-Type", "application/json")
                        .header(session.csrfHeader(), session.csrfToken())
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
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

    private void assertAnalysisNotFound(HttpResponse<String> response) {
        assertEquals(404, response.statusCode());
        assertEquals("application/json", mediaType(response));
        assertEquals(ANALYSIS_NOT_FOUND, response.body());
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

    private static JsonNode findDiscrepancy(JsonNode reconciliation, String type) {
        for (JsonNode discrepancy : reconciliation.get("discrepancies")) {
            if (type.equals(discrepancy.get("type").asString())) {
                return discrepancy;
            }
        }
        throw new AssertionError("Expected persisted discrepancy was absent from the response.");
    }

    private static String reconciliationPath(UUID analysisId) {
        return "/api/analyses/" + analysisId + "/reconciliation";
    }

    private static String reconcilePath(UUID analysisId) {
        return "/api/analyses/" + analysisId + "/reconcile";
    }

    private static String discrepancyPath(UUID analysisId, UUID discrepancyId) {
        return "/api/analyses/" + analysisId + "/discrepancies/" + discrepancyId;
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

    private static String sha256() {
        String value = UUID.randomUUID().toString().replace("-", "");
        return value + value;
    }

    private record UserFixture(UUID id, String email) {
    }

    private record CsrfTicket(String sessionCookie, String headerName, String token) {
    }

    private record AuthenticatedSession(String cookie, String csrfHeader, String csrfToken) {
    }

    private record ReconciliationFixture(UUID analysisId, String invoiceNumber, String supplierKey) {
    }
}
