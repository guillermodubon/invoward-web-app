package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.support.ai.FakeDocumentIntelligence;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeDocumentStorage;
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

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearTestDoubles() {
        storage.clear();
        intelligence.reset();
    }

    @Test
    void guestOwnerCompletesExtractionWhileForeignAndInvalidCookiesRemainHiddenWithoutSlidingExpiry()
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

        intelligence.setClassification(referenceDocumentId, DocumentType.QUOTE);
        intelligence.setClassification(invoiceDocumentId, DocumentType.INVOICE);
        intelligence.setExtraction(extractionDraft());
        markGuestActivityStale(guestA, Instant.now().minusSeconds(30));
        HttpResponse<String> detected = postWithoutBody(
                baseUri,
                analysisPath(guestA.analysisId()) + "/documents/detect-types",
                guestA.csrf(),
                guestA.guestCookie());
        assertEquals(200, detected.statusCode(), detected.body());
        assertEquals(2, objectMapper.readTree(detected.body()).size());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, Instant.now().minusSeconds(30), 2);
        assertEquals(2, intelligence.totalClassifyCalls());

        markGuestActivityStale(guestA, Instant.now().minusSeconds(30));
        HttpResponse<String> started = postJson(
                baseUri,
                analysisPath(guestA.analysisId()) + "/extract",
                guestA.csrf(),
                guestA.guestCookie(),
                START_EXTRACTION_BODY);
        assertEquals(200, started.statusCode(), started.body());
        JsonNode initialReview = objectMapper.readTree(started.body());
        assertEquals("DRAFT", initialReview.get("reference").get("status").asString());
        assertEquals("DRAFT", initialReview.get("invoice").get("status").asString());
        assertEquals(1, initialReview.get("reference").get("lines").size());
        assertEquals(1, initialReview.get("invoice").get("lines").size());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, Instant.now().minusSeconds(30), 2);
        assertEquals(2, intelligence.extractCalls());

        String extractionPath = analysisPath(guestA.analysisId()) + "/extraction";
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
        assertEquals(204, confirmed.statusCode(), confirmed.body());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, Instant.now().minusSeconds(30), 2);

        markGuestActivityStale(guestA, Instant.now().minusSeconds(30));
        HttpResponse<String> confirmedReviewResponse = get(
                baseUri, extractionPath, guestA.csrf(), guestA.guestCookie());
        assertEquals(200, confirmedReviewResponse.statusCode());
        JsonNode confirmedReview = objectMapper.readTree(confirmedReviewResponse.body());
        assertEquals("CONFIRMED", confirmedReview.get("reference").get("status").asString());
        assertEquals("CONFIRMED", confirmedReview.get("invoice").get("status").asString());
        assertSuccessfulActivityWithoutSlidingExpiry(guestA, Instant.now().minusSeconds(30), 2);

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
        UUID expiredGuestId = insertExpiredGuestSession();
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
        int classificationCallsBeforeRejectedRequests = intelligence.totalClassifyCalls();
        int extractionCallsBeforeRejectedRequests = intelligence.extractCalls();

        List<GuestAccess> invalidGuestAccesses = List.of(
                new GuestAccess("foreign guest", guestB.csrf(), guestB.guestCookie()),
                new GuestAccess("missing guest cookie", guestA.csrf(), null),
                new GuestAccess("malformed guest cookie", guestA.csrf(), "not-a-uuid"),
                new GuestAccess("unknown guest cookie", guestA.csrf(), unknownGuestCookie),
                new GuestAccess("expired guest cookie", guestA.csrf(), expiredGuestId.toString()));

        for (GuestAccess access : invalidGuestAccesses) {
            assertExtractionRoutesNotFound(baseUri, guestA.analysisId(), updatedReview, access);
            assertExtractionRoutesNotFound(baseUri, pendingAnalysis.analysisId(), updatedReview, access);
        }

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
        assertEquals(200, postWithoutBody(baseUri,
                analysisPath(firstAnalysis.analysisId()) + "/documents/detect-types",
                firstAnalysis.csrf(), firstAnalysis.guestCookie()).statusCode());

        String firstExtractionPath = analysisPath(firstAnalysis.analysisId()) + "/extraction";
        HttpResponse<String> firstStarted = postJson(
                baseUri, analysisPath(firstAnalysis.analysisId()) + "/extract",
                firstAnalysis.csrf(), firstAnalysis.guestCookie(), START_EXTRACTION_BODY);
        assertEquals(200, firstStarted.statusCode(), firstStarted.body());
        JsonNode firstDraft = objectMapper.readTree(firstStarted.body());
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

        assertEquals(204, postJson(
                baseUri, firstExtractionPath + "/confirm", firstAnalysis.csrf(), firstAnalysis.guestCookie(),
                confirmationRequest(firstCorrectedReview)).statusCode());
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
        assertEquals(200, postWithoutBody(baseUri,
                analysisPath(secondAnalysis.analysisId()) + "/documents/detect-types",
                secondAnalysis.csrf(), secondAnalysis.guestCookie()).statusCode());

        int aiCallsBeforeCacheHit = intelligence.extractCalls();
        EnumMap<FakeDocumentStorage.Operation, Integer> storageCallsBeforeCacheHit = storageCallCounts();
        String secondExtractionPath = analysisPath(secondAnalysis.analysisId()) + "/extraction";
        HttpResponse<String> secondStarted = postJson(
                baseUri, analysisPath(secondAnalysis.analysisId()) + "/extract",
                secondAnalysis.csrf(), secondAnalysis.guestCookie(), START_EXTRACTION_BODY);
        assertEquals(200, secondStarted.statusCode(), secondStarted.body());
        JsonNode secondDraft = objectMapper.readTree(secondStarted.body());
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
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutBody(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Cookie", cookies(csrf.sessionCookie(), guestCookie))
                .header("X-CSRF-TOKEN", csrf.token())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie, String body) throws Exception {
        HttpRequest request = jsonRequest(baseUri, path, csrf, guestCookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(
            URI baseUri, String path, CsrfTicket csrf, String guestCookie, String body) throws Exception {
        HttpRequest request = jsonRequest(baseUri, path, csrf, guestCookie)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
                updatedDocument(review.get("reference"), "QUOTE", "Guest-corrected reference line"),
                updatedDocument(review.get("invoice"), "INVOICE", "Guest-corrected invoice line"))));
    }

    private static Map<String, Object> updatedDocument(JsonNode document, String confirmedType, String description) {
        JsonNode line = document.get("lines").get(0);
        Map<String, Object> updatedLine = Map.of(
                "id", line.get("id").asString(),
                "itemCode", line.get("itemCode").asString(),
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

    private void markGuestActivityStale(GuestAnalysis guest, Instant oldActivity) {
        Instant createdAt = guestRow(guest.guestSessionId()).createdAt();
        Instant validStaleActivity = oldActivity.isBefore(createdAt) ? createdAt : oldActivity;
        jdbcTemplate.update("UPDATE invoward.guest_sessions SET last_seen_at = ? WHERE id = ?",
                Timestamp.from(validStaleActivity), guest.guestSessionId());
    }

    private UUID insertExpiredGuestSession() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(Duration.ofHours(48));
        Instant lastSeenAt = createdAt.plus(Duration.ofHours(1));
        Instant expiresAt = createdAt.plus(Duration.ofHours(24));
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, id, Timestamp.from(expiresAt), Timestamp.from(lastSeenAt), Timestamp.from(createdAt));
        return id;
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

    private void assertNotFound(HttpResponse<String> response, String guestContext) {
        assertEquals(404, response.statusCode(), guestContext + ": " + response.body());
        assertEquals("application/json",
                response.headers().firstValue("Content-Type").orElseThrow().split(";", 2)[0]);
        assertEquals(ANALYSIS_NOT_FOUND, response.body(), guestContext);
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
}
