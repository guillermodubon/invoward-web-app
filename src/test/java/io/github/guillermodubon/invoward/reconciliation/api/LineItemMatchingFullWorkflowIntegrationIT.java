package io.github.guillermodubon.invoward.reconciliation.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
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
import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import io.github.guillermodubon.invoward.reconciliation.application.port.LineItemMatchRepository;
import io.github.guillermodubon.invoward.reconciliation.application.service.RunLineItemMatchingService;
import io.github.guillermodubon.invoward.reconciliation.domain.LineItemMatch;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchMethod;
import io.github.guillermodubon.invoward.reconciliation.domain.LineMatchStatus;
import io.github.guillermodubon.invoward.support.ai.FakeAmbiguousLineMatcher;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Covers matching through review and confirmation using the real HTTP and PostgreSQL boundaries. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class LineItemMatchingFullWorkflowIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String AMBIGUOUS_DESCRIPTION = "blue paper archive storage box";

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
    private RunLineItemMatchingService runMatching;

    @MockitoBean
    private AmbiguousLineMatcher ambiguousLineMatcher;

    private final FakeAmbiguousLineMatcher fakeMatcher = new FakeAmbiguousLineMatcher();

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void runGetManualCorrectionAndConfirmPersistTheCompleteWorkflow() throws Exception {
        fakeMatcher.reset();
        fakeMatcher.configureCandidate("C2");
        when(ambiguousLineMatcher.suggest(any()))
                .thenAnswer(invocation -> fakeMatcher.suggest(invocation.getArgument(0)));

        GuestSession guest = guestSessionService.create();
        GuestSessionOwner owner = new GuestSessionOwner(guest.id(), guest.expiresAt());
        MatchingFixture fixture = createMatchingFixture(owner);

        List<LineItemMatch> generated = runMatching.run(fixture.analysisId(), owner);
        assertEquals(1, fakeMatcher.callCount(), "Only the ambiguous lines should use the fake matcher.");
        assertCompleteCover(fixture, generated);
        assertEquals("C2", fakeMatcher.lastInput().orElseThrow().invoiceCandidates().get(1).label());

        LineItemMatch suggestion = generated.stream()
                .filter(match -> match.status() == LineMatchStatus.NEEDS_REVIEW)
                .findFirst()
                .orElseThrow();
        assertEquals(LineMatchMethod.AI, suggestion.method());
        assertWorkflow(fixture, AnalysisStatus.AWAITING_MATCH_REVIEW);

        CsrfTicket csrf = csrfTicket();
        URI baseUri = applicationUri();
        HttpResponse<String> getResponse = get(
                baseUri, matchesPath(fixture.analysisId()), csrf.sessionCookie(), guest.id().toString());
        assertEquals(200, getResponse.statusCode(), getResponse.body());
        JsonNode matchSet = objectMapper.readTree(getResponse.body());
        assertEquals("AWAITING_MATCH_REVIEW", matchSet.get("analysisStatus").asString());
        assertTrue(matchSet.get("reviewRequired").asBoolean());
        assertEquals(generated.size(), matchSet.get("matches").size());
        JsonNode suggestedMatch = findMatch(matchSet, suggestion.id());
        assertEquals("NEEDS_REVIEW", suggestedMatch.get("status").asString());
        assertEquals("AI", suggestedMatch.get("method").asString());
        assertEquals(0, suggestedMatch.get("version").asInt());
        assertEquals(fixture.referenceLineIds().get(1).toString(),
                suggestedMatch.get("reference").get("lineItemId").asString());
        assertEquals(fixture.invoiceLineIds().get(2).toString(),
                suggestedMatch.get("invoice").get("lineItemId").asString());
        assertEquals(1, fakeMatcher.callCount(), "Reading persisted matches must not rerun AI matching.");

        String correction = objectMapper.writeValueAsString(Map.of(
                "expectedVersion", 0,
                "action", "MATCH_WITH",
                "referenceLineItemId", fixture.referenceLineIds().get(1).toString(),
                "invoiceLineItemId", fixture.invoiceLineIds().get(1).toString()));
        HttpResponse<String> patchResponse = patchJson(
                baseUri,
                matchPath(fixture.analysisId(), suggestion.id()),
                csrf,
                guest.id().toString(),
                correction);
        assertEquals(200, patchResponse.statusCode(), patchResponse.body());
        JsonNode correctedSet = objectMapper.readTree(patchResponse.body());
        assertEquals(1, fakeMatcher.callCount(), "PATCH must not invoke the matcher again.");
        JsonNode manualMatch = findMatch(correctedSet, suggestion.id());
        assertEquals("MATCHED", manualMatch.get("status").asString());
        assertEquals("MANUAL", manualMatch.get("method").asString());
        assertTrue(manualMatch.get("confidence").isNull());
        assertEquals(1, manualMatch.get("version").asInt());
        assertEquals(fixture.invoiceLineIds().get(1).toString(),
                manualMatch.get("invoice").get("lineItemId").asString());
        assertCompleteCover(fixture, lineItemMatchRepository.findByAnalysisId(fixture.analysisId()));
        assertEquals(AnalysisStatus.AWAITING_MATCH_REVIEW,
                analysisRepository.findOwnedById(fixture.analysisId(), owner, clock.instant())
                        .orElseThrow().status());

        HttpResponse<String> confirmResponse = postWithoutBody(
                baseUri,
                matchesPath(fixture.analysisId()) + "/confirm",
                csrf,
                guest.id().toString());
        assertEquals(204, confirmResponse.statusCode(), confirmResponse.body());

        List<LineItemMatch> persisted = lineItemMatchRepository.findByAnalysisId(fixture.analysisId());
        assertEquals(generated.size(), persisted.size());
        assertCompleteCover(fixture, persisted);
        assertTrue(persisted.stream().allMatch(match -> match.reviewedAt() != null));
        assertWorkflow(fixture, AnalysisStatus.RECONCILING);
        assertEquals(0, countRows("invoward.reconciliation_lines", fixture.analysisId()));
        assertEquals(0, countRows("invoward.discrepancies", fixture.analysisId()));
    }

    private MatchingFixture createMatchingFixture(AnalysisOwner owner) {
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
        analysisJobRepository.update(job.waitForMatching(matchingAt));

        Instant documentsAt = startedAt.plusSeconds(5);
        Document reference = createDocument(
                analysis, DocumentRole.REFERENCE, DocumentType.QUOTE, documentsAt);
        Document invoice = createDocument(
                analysis, DocumentRole.INVOICE, DocumentType.INVOICE, documentsAt);
        List<UUID> referenceLineIds = createConfirmedExtraction(reference, List.of(
                new LineSpec("ABC-123", "accepted widget"),
                new LineSpec(null, AMBIGUOUS_DESCRIPTION)), documentsAt.plusSeconds(1));
        List<UUID> invoiceLineIds = createConfirmedExtraction(invoice, List.of(
                new LineSpec(" abc-123 ", "invoice widget"),
                new LineSpec(null, AMBIGUOUS_DESCRIPTION),
                new LineSpec(null, AMBIGUOUS_DESCRIPTION)), documentsAt.plusSeconds(1));
        return new MatchingFixture(analysisId, owner, referenceLineIds, invoiceLineIds);
    }

    private Document createDocument(
            Analysis analysis, DocumentRole role, DocumentType type, Instant createdAt) {
        return documentRepository.create(Document.createUploaded(
                        UUID.randomUUID(), analysis.id(), role, role + ".pdf", "application/pdf", 1024,
                        1, sha256(), "matching-workflow/" + UUID.randomUUID(), analysis.expiresAt(), createdAt)
                .withDetectedType(type)
                .withConfirmedType(type));
    }

    private List<UUID> createConfirmedExtraction(
            Document document, List<LineSpec> specifications, Instant createdAt) {
        List<ExtractedLineItem> lines = new ArrayList<>();
        for (int position = 0; position < specifications.size(); position++) {
            LineSpec specification = specifications.get(position);
            lines.add(new ExtractedLineItem(
                    position,
                    specification.itemCode(),
                    specification.description(),
                    new BigDecimal("2.0000"),
                    "each",
                    new BigDecimal("37.1200"),
                    new BigDecimal("1.0000"),
                    new BigDecimal("2.0000"),
                    new BigDecimal("75.2400"),
                    1,
                    "synthetic evidence",
                    null));
        }
        ExtractedDocument draft = ExtractedDocument.draft(
                ExtractionSource.AI, "Test Vendor", null, null, "USD",
                null, null, null, null, lines);
        PersistedExtraction created = extractedDocumentRepository.create(
                document.id(), draft, "matching-workflow-test-v1", "test-model", 1, createdAt);
        Instant confirmedAt = createdAt.plusSeconds(1);
        PersistedExtraction confirmed = extractedDocumentRepository.confirm(
                created.withExtraction(created.extraction().confirm(confirmedAt)), confirmedAt).orElseThrow();
        return confirmed.lineItemIds();
    }

    private void assertWorkflow(MatchingFixture fixture, AnalysisStatus expectedStatus) {
        Analysis analysis = analysisRepository.findOwnedById(
                fixture.analysisId(), fixture.owner(), clock.instant()).orElseThrow();
        AnalysisJob job = analysisJobRepository.findByAnalysisId(fixture.analysisId()).orElseThrow();
        assertEquals(expectedStatus, analysis.status());
        assertEquals(AnalysisJobStatus.WAITING_FOR_USER, job.status());
        assertEquals(expectedStatus, job.currentStage());
        assertEquals(0, job.attemptCount());
        assertFalse(job.retryable());
        assertNull(job.startedAt());
        assertNull(job.completedAt());
    }

    private long countRows(String table, UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE analysis_id = ?", Long.class, analysisId);
    }

    private void assertCompleteCover(MatchingFixture fixture, List<LineItemMatch> matches) {
        List<UUID> referenceIds = matches.stream()
                .map(LineItemMatch::referenceLineItemId)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<UUID> invoiceIds = matches.stream()
                .map(LineItemMatch::invoiceLineItemId)
                .filter(java.util.Objects::nonNull)
                .toList();
        assertEquals(new HashSet<>(fixture.referenceLineIds()), new HashSet<>(referenceIds));
        assertEquals(new HashSet<>(fixture.invoiceLineIds()), new HashSet<>(invoiceIds));
        assertEquals(referenceIds.size(), new HashSet<>(referenceIds).size());
        assertEquals(invoiceIds.size(), new HashSet<>(invoiceIds).size());
    }

    private CsrfTicket csrfTicket() throws Exception {
        HttpResponse<String> response = get(applicationUri(), "/api/auth/csrf", null, null);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode csrf = objectMapper.readTree(response.body());
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        assertFalse(csrf.get("token").asString().isBlank());
        return new CsrfTicket(cookieValue(response, SESSION_COOKIE), csrf.get("token").asString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, String sessionCookie, String guestCookie) throws Exception {
        return httpClient.send(
                request(baseUri, path, sessionCookie, guestCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie, String body) throws Exception {
        HttpRequest request = request(baseUri, path, csrf.sessionCookie(), guestCookie)
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrf.token())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutBody(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie) throws Exception {
        HttpRequest request = request(baseUri, path, csrf.sessionCookie(), guestCookie)
                .header("X-CSRF-TOKEN", csrf.token())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

    private JsonNode findMatch(JsonNode matchSet, UUID matchId) {
        for (JsonNode match : matchSet.get("matches")) {
            if (matchId.toString().equals(match.get("id").asString())) {
                return match;
            }
        }
        throw new AssertionError("Expected persisted match was absent from the HTTP response.");
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

    private record LineSpec(String itemCode, String description) {
    }

    private record MatchingFixture(
            UUID analysisId,
            AnalysisOwner owner,
            List<UUID> referenceLineIds,
            List<UUID> invoiceLineIds) {
        private MatchingFixture {
            referenceLineIds = List.copyOf(referenceLineIds);
            invoiceLineIds = List.copyOf(invoiceLineIds);
        }
    }
}
