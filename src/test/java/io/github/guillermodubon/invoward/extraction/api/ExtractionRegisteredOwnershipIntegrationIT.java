package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.support.ai.FakeDocumentIntelligence;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.model.chat=none",
                "spring.ai.google.genai.chat.model=integration-test-model",
                "invoward.email.provider=disabled"
        })
@Import(ExtractionRegisteredOwnershipIntegrationIT.TestProviderConfiguration.class)
class ExtractionRegisteredOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String RAW_PASSWORD = "registered extraction integration passphrase";
    private static final String ANALYSIS_NOT_FOUND =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
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
    void registeredOwnerCompletesExtractionAndOtherUserGetsUniformNotFoundBeforePrivateAccess()
            throws Exception {
        UserFixture userA = insertActiveVerifiedUser("extraction-owner-a");
        UserFixture userB = insertActiveVerifiedUser("extraction-owner-b");
        UUID validGuestSessionId = jdbcTemplate.execute(
                (ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
        long guestSessionCountBefore = countGuestSessions();

        URI baseUri = applicationUri();
        AuthenticatedSession sessionA = login(baseUri, userA);
        AuthenticatedSession sessionB = login(baseUri, userB);

        HttpResponse<String> createdAnalysis = postJson(
                baseUri, "/api/analyses", sessionA, validGuestSessionId, "{}");
        assertEquals(202, createdAnalysis.statusCode());
        UUID analysisId = UUID.fromString(objectMapper.readTree(createdAnalysis.body()).get("id").asString());
        assertEquals("/api/analyses/" + analysisId,
                createdAnalysis.headers().firstValue("Location").orElseThrow());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM invoward.analyses
                WHERE id = ? AND user_id = ? AND guest_session_id IS NULL AND expires_at IS NULL
                """, Integer.class, analysisId, userA.id()));

        UUID referenceDocumentId = uploadDocument(
                baseUri, analysisId, sessionA, validGuestSessionId, "REFERENCE", validPdf("reference"));
        UUID invoiceDocumentId = uploadDocument(
                baseUri, analysisId, sessionA, validGuestSessionId, "INVOICE", validPdf("invoice"));
        assertEquals(2, storage.operationCalls(FakeDocumentStorage.Operation.STORE));

        intelligence.setClassification(referenceDocumentId, DocumentType.QUOTE);
        intelligence.setClassification(invoiceDocumentId, DocumentType.INVOICE);
        intelligence.setExtraction(extractionDraft());
        HttpResponse<String> detected = postWithoutBody(
                baseUri, analysisPath(analysisId) + "/documents/detect-types", sessionA, validGuestSessionId);
        assertEquals(200, detected.statusCode());
        assertEquals(2, objectMapper.readTree(detected.body()).size());
        assertEquals(2, intelligence.totalClassifyCalls());

        HttpResponse<String> started = postJson(
                baseUri,
                analysisPath(analysisId) + "/extract",
                sessionA,
                validGuestSessionId,
                """
                {"referenceType":"QUOTE","invoiceType":"INVOICE"}
                """);
        assertEquals(200, started.statusCode());
        JsonNode initialReview = objectMapper.readTree(started.body());
        assertEquals("DRAFT", initialReview.get("reference").get("status").asString());
        assertEquals("DRAFT", initialReview.get("invoice").get("status").asString());
        assertEquals(1, initialReview.get("reference").get("lines").size());
        assertEquals(1, initialReview.get("invoice").get("lines").size());
        assertEquals(2, intelligence.extractCalls());

        String path = analysisPath(analysisId) + "/extraction";
        HttpResponse<String> readReview = get(baseUri, path, sessionA, validGuestSessionId);
        assertEquals(200, readReview.statusCode());
        assertEquals("DRAFT", objectMapper.readTree(readReview.body())
                .get("reference").get("status").asString());

        HttpResponse<String> updated = putJson(
                baseUri, path, sessionA, validGuestSessionId, updatedReviewRequest(initialReview));
        assertEquals(200, updated.statusCode());
        JsonNode updatedReview = objectMapper.readTree(updated.body());
        assertEquals("User-corrected supplier", updatedReview.get("reference").get("vendorName").asString());
        assertEquals("User-corrected reference line",
                updatedReview.get("reference").get("lines").get(0).get("description").asString());

        HttpResponse<String> confirmed = postJson(
                baseUri,
                path + "/confirm",
                sessionA,
                validGuestSessionId,
                confirmationRequest(updatedReview));
        assertEquals(204, confirmed.statusCode());

        HttpResponse<String> confirmedReview = get(baseUri, path, sessionA, validGuestSessionId);
        assertEquals(200, confirmedReview.statusCode());
        JsonNode confirmedBody = objectMapper.readTree(confirmedReview.body());
        assertEquals("CONFIRMED", confirmedBody.get("reference").get("status").asString());
        assertEquals("CONFIRMED", confirmedBody.get("invoice").get("status").asString());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.analyses WHERE id = ? AND status = 'MATCHING'",
                Integer.class,
                analysisId));
        assertEquals(guestSessionCountBefore, countGuestSessions(),
                "an authenticated request carrying a valid guest cookie must not create a guest session");

        EnumMap<FakeDocumentStorage.Operation, Integer> storageCallsBeforeOtherUser = storageCallCounts();
        int classificationCallsBeforeOtherUser = intelligence.totalClassifyCalls();
        int extractionCallsBeforeOtherUser = intelligence.extractCalls();

        String analysisPath = analysisPath(analysisId);
        assertNotFound(postWithoutBody(
                baseUri, analysisPath + "/documents/detect-types", sessionB, validGuestSessionId));
        assertNotFound(postJson(
                baseUri, analysisPath + "/extract", sessionB, validGuestSessionId,
                """
                {"referenceType":"QUOTE","invoiceType":"INVOICE"}
                """));
        assertNotFound(get(baseUri, path, sessionB, validGuestSessionId));
        assertNotFound(putJson(
                baseUri, path, sessionB, validGuestSessionId, updatedReviewRequest(updatedReview)));
        assertNotFound(postJson(
                baseUri, path + "/confirm", sessionB, validGuestSessionId,
                confirmationRequest(updatedReview)));

        assertEquals(storageCallsBeforeOtherUser, storageCallCounts(),
                "ownership must be rejected before any storage operation");
        assertEquals(classificationCallsBeforeOtherUser, intelligence.totalClassifyCalls(),
                "ownership must be rejected before classification");
        assertEquals(extractionCallsBeforeOtherUser, intelligence.extractCalls(),
                "ownership must be rejected before extraction");
    }

    private UUID uploadDocument(
            URI baseUri,
            UUID analysisId,
            AuthenticatedSession session,
            UUID guestSessionId,
            String role,
            byte[] pdf) throws Exception {
        String boundary = "InvoWardBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"role\"\r\n\r\n"
                + role + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + role.toLowerCase()
                + "-original.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(pdf);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));

        HttpRequest request = HttpRequest.newBuilder(
                        baseUri.resolve(analysisPath(analysisId) + "/documents"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", cookies(session.cookie(), guestSessionId))
                .header("X-CSRF-TOKEN", session.csrf().token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
    }

    private AuthenticatedSession login(URI baseUri, UserFixture user) throws Exception {
        CsrfTicket preAuthentication = csrfTicket(baseUri, null);
        HttpResponse<String> response = postJson(
                baseUri,
                "/api/auth/login",
                new AuthenticatedSession(preAuthentication.sessionCookie(), preAuthentication),
                null,
                objectMapper.writeValueAsString(Map.of("email", user.email(), "password", RAW_PASSWORD)));
        assertEquals(200, response.statusCode());
        String authenticatedSessionCookie = cookieValue(response, SESSION_COOKIE);
        assertNotEquals(preAuthentication.sessionCookie(), authenticatedSessionCookie,
                "successful login must rotate the pre-authentication session id");
        CsrfTicket authenticatedCsrf = csrfTicket(baseUri, authenticatedSessionCookie);
        return new AuthenticatedSession(authenticatedCsrf.sessionCookie(), authenticatedCsrf);
    }

    private CsrfTicket csrfTicket(URI baseUri, String sessionCookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve("/api/auth/csrf"))
                .timeout(Duration.ofSeconds(10));
        if (sessionCookie != null) {
            request.header("Cookie", SESSION_COOKIE + "=" + sessionCookie);
        }
        HttpResponse<String> response = httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode csrf = objectMapper.readTree(response.body());
        String updatedSessionCookie = updatedCookieValue(response, SESSION_COOKIE, sessionCookie);
        assertNotNull(updatedSessionCookie);
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        assertFalse(csrf.get("token").asString().isBlank());
        return new CsrfTicket(updatedSessionCookie, csrf.get("token").asString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, AuthenticatedSession session, UUID guestSessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", cookies(session.cookie(), guestSessionId))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutBody(
            URI baseUri, String path, AuthenticatedSession session, UUID guestSessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", cookies(session.cookie(), guestSessionId))
                .header("X-CSRF-TOKEN", session.csrf().token())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(
            URI baseUri,
            String path,
            AuthenticatedSession session,
            UUID guestSessionId,
            String body) throws Exception {
        HttpRequest request = jsonRequest(baseUri, path, session, guestSessionId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(
            URI baseUri,
            String path,
            AuthenticatedSession session,
            UUID guestSessionId,
            String body) throws Exception {
        HttpRequest request = jsonRequest(baseUri, path, session, guestSessionId)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder jsonRequest(
            URI baseUri, String path, AuthenticatedSession session, UUID guestSessionId) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Cookie", cookies(session.cookie(), guestSessionId))
                .header("X-CSRF-TOKEN", session.csrf().token());
    }

    private String updatedReviewRequest(JsonNode review) throws Exception {
        return objectMapper.writeValueAsString(Map.of("documents", List.of(
                updatedDocument(review.get("reference"), "QUOTE", "User-corrected reference line"),
                updatedDocument(review.get("invoice"), "INVOICE", "User-corrected invoice line"))));
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
                Map.entry("vendorName", "User-corrected supplier"),
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

    private EnumMap<FakeDocumentStorage.Operation, Integer> storageCallCounts() {
        EnumMap<FakeDocumentStorage.Operation, Integer> counts = new EnumMap<>(FakeDocumentStorage.Operation.class);
        for (FakeDocumentStorage.Operation operation : FakeDocumentStorage.Operation.values()) {
            counts.put(operation, storage.operationCalls(operation));
        }
        return counts;
    }

    private void assertNotFound(HttpResponse<String> response) {
        assertEquals(404, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow().split(";", 2)[0]);
        assertEquals(ANALYSIS_NOT_FOUND, response.body());
    }

    private UserFixture insertActiveVerifiedUser(String prefix) {
        UUID id = UUID.randomUUID();
        String email = prefix + "-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Extraction Integration User', ?, ?, true, ?)
                """, id, email, passwordEncoder.encode(RAW_PASSWORD), UserStatus.ACTIVE.name());
        return new UserFixture(id, email);
    }

    private long countGuestSessions() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoward.guest_sessions", Long.class);
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

    private static ExtractionDraft extractionDraft() {
        BigDecimal amount = new BigDecimal("10.0000");
        BigDecimal zero = BigDecimal.ZERO.setScale(4);
        return new ExtractionDraft(
                "Acme Supplies",
                "DOC-100",
                "2026-10-04",
                "USD",
                amount,
                zero,
                zero,
                amount,
                List.of(new ExtractionDraft.Line(
                        "ITEM-1",
                        "Fastener pack",
                        BigDecimal.ONE.setScale(4),
                        "each",
                        amount,
                        zero,
                        zero,
                        amount,
                        1,
                        "Fastener pack 10.00",
                        new ExtractionDraft.RawBoundingBox(0.1, 0.2, 0.8, 0.9))));
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private static String analysisPath(UUID analysisId) {
        return "/api/analyses/" + analysisId;
    }

    private static String cookies(String sessionCookie, UUID guestSessionId) {
        List<String> values = new ArrayList<>();
        values.add(SESSION_COOKIE + "=" + sessionCookie);
        if (guestSessionId != null) {
            values.add(GUEST_COOKIE + "=" + guestSessionId);
        }
        return String.join("; ", values);
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

    private record UserFixture(UUID id, String email) {
    }

    private record CsrfTicket(String sessionCookie, String token) {
    }

    private record AuthenticatedSession(String cookie, CsrfTicket csrf) {
    }
}
