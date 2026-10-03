package io.github.guillermodubon.invoward.document.api;

import io.github.guillermodubon.invoward.analysis.application.service.CreateAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeDocumentStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "invoward.email.provider=disabled",
                "invoward.documents.max-combined-size=1KB"
        })
@Import(DocumentRegisteredOwnershipIntegrationIT.TestStorageConfiguration.class)
class DocumentRegisteredOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String RAW_PASSWORD = "document ownership integration passphrase";
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
    private CreateAnalysisService createAnalysisService;

    @Autowired
    private FakeDocumentStorage storage;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearFakeStorage() {
        storage.clear();
    }

    @Test
    void registeredOwnerCanUploadWhileOtherOwnersRemainHiddenEvenWithGuestCookie() throws Exception {
        UserFixture userA = insertActiveVerifiedUser("document-user-a");
        UserFixture userB = insertActiveVerifiedUser("document-user-b");
        UUID registeredAnalysisId = createAnalysis(new RegisteredUserOwner(userA.id()));

        UUID guestSessionId = jdbcTemplate.execute(
                (ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
        Instant guestExpiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.guest_sessions WHERE id = ?",
                (result, rowNumber) -> result.getTimestamp("expires_at").toInstant(),
                guestSessionId);
        UUID guestAnalysisId = createAnalysis(new GuestSessionOwner(guestSessionId, guestExpiresAt));

        URI baseUri = applicationUri();
        AuthenticatedSession sessionA = login(baseUri, userA);
        AuthenticatedSession sessionB = login(baseUri, userB);
        byte[] pdf = validPdf();

        HttpResponse<String> ownerUpload = upload(
                baseUri,
                registeredAnalysisId,
                sessionA,
                null,
                "REFERENCE",
                pdf);

        assertEquals(201, ownerUpload.statusCode());
        assertEquals("application/json", mediaType(ownerUpload));
        JsonNode document = objectMapper.readTree(ownerUpload.body());
        UUID documentId = UUID.fromString(document.get("id").asString());
        assertEquals("REFERENCE", document.get("role").asString());
        assertTrue(document.get("detectedType").isNull());
        assertTrue(document.get("confirmedType").isNull());
        assertEquals("reference-original.pdf", document.get("originalFilename").asString());
        assertEquals(pdf.length, document.get("sizeBytes").asInt());
        assertEquals("/api/analyses/" + registeredAnalysisId + "/documents/" + documentId,
                ownerUpload.headers().firstValue("Location").orElseThrow());
        assertFalse(ownerUpload.body().contains(userA.id().toString()));
        assertFalse(ownerUpload.body().contains(guestSessionId.toString()));
        assertFalse(ownerUpload.headers().allValues("Set-Cookie").stream()
                .anyMatch(value -> value.startsWith(GUEST_COOKIE + "=")));
        assertEquals(1, storage.storedObjectCount());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.documents WHERE id = ? AND analysis_id = ?",
                Integer.class,
                documentId,
                registeredAnalysisId));

        HttpResponse<String> otherRegisteredUserUpload = upload(
                baseUri,
                registeredAnalysisId,
                sessionB,
                null,
                "INVOICE",
                pdf);
        assertAnalysisNotFound(otherRegisteredUserUpload, userA.id(), null);
        assertEquals(1, storage.storedObjectCount(),
                "failed parent ownership must be checked before object storage");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.documents WHERE analysis_id = ?",
                Integer.class,
                registeredAnalysisId));

        HttpResponse<String> authenticatedUserWithGuestCookie = upload(
                baseUri,
                guestAnalysisId,
                sessionA,
                guestSessionId,
                "REFERENCE",
                pdf);
        assertAnalysisNotFound(authenticatedUserWithGuestCookie, userA.id(), guestSessionId);
        assertEquals(1, storage.storedObjectCount(),
                "authenticated identity must take precedence over the guest bearer cookie");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.documents WHERE analysis_id = ?",
                Integer.class,
                guestAnalysisId));
    }

    private void assertAnalysisNotFound(
            HttpResponse<String> response, UUID protectedOwnerId, UUID guestSessionId) {
        assertEquals(404, response.statusCode());
        assertEquals("application/json", mediaType(response));
        assertEquals(ANALYSIS_NOT_FOUND, response.body());
        assertFalse(response.body().contains(protectedOwnerId.toString()));
        if (guestSessionId != null) {
            assertFalse(response.body().contains(guestSessionId.toString()));
        }
    }

    private AuthenticatedSession login(URI baseUri, UserFixture user) throws Exception {
        CsrfTicket preAuthentication = csrfTicket(baseUri, null);
        HttpResponse<String> response = postJson(
                baseUri,
                "/api/auth/login",
                preAuthentication.sessionCookie(),
                preAuthentication.token(),
                objectMapper.writeValueAsString(Map.of("email", user.email(), "password", RAW_PASSWORD)));
        assertEquals(200, response.statusCode());
        String authenticatedCookie = cookieValue(response, SESSION_COOKIE);
        assertNotEquals(preAuthentication.sessionCookie(), authenticatedCookie,
                "Successful login must rotate the pre-authentication session ID.");
        CsrfTicket authenticatedCsrf = csrfTicket(baseUri, authenticatedCookie);
        return new AuthenticatedSession(authenticatedCsrf.sessionCookie(), authenticatedCsrf);
    }

    private CsrfTicket csrfTicket(URI baseUri, String sessionCookie) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", sessionCookie, null);
        assertEquals(200, response.statusCode());
        JsonNode csrf = objectMapper.readTree(response.body());
        String updatedCookie = updatedCookieValue(response, SESSION_COOKIE, sessionCookie);
        assertNotNull(updatedCookie);
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        assertFalse(csrf.get("token").asString().isBlank());
        return new CsrfTicket(updatedCookie, csrf.get("token").asString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, String sessionCookie, UUID guestSessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        String cookieHeader = cookies(sessionCookie, guestSessionId);
        if (!cookieHeader.isBlank()) {
            request.header("Cookie", cookieHeader);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(
            URI baseUri, String path, String sessionCookie, String csrfToken, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Cookie", SESSION_COOKIE + "=" + sessionCookie)
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(
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
                + "Content-Disposition: form-data; name=\"file\"; filename=\"reference-original.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(pdf);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));

        HttpRequest.Builder request = HttpRequest.newBuilder(
                        baseUri.resolve("/api/analyses/" + analysisId + "/documents"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", cookies(session.cookie(), guestSessionId))
                .header("X-CSRF-TOKEN", session.csrf().token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()));
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private UserFixture insertActiveVerifiedUser(String emailPrefix) {
        UUID id = UUID.randomUUID();
        String email = emailPrefix + "-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Document Integration User', ?, ?, true, ?)
                """, id, email, passwordEncoder.encode(RAW_PASSWORD), UserStatus.ACTIVE.name());
        return new UserFixture(id, email);
    }

    private UUID createAnalysis(RegisteredUserOwner owner) {
        return createAnalysisService.create(owner, PriceTolerance.exactMatch()).id();
    }

    private UUID createAnalysis(GuestSessionOwner owner) {
        return createAnalysisService.create(owner, PriceTolerance.exactMatch()).id();
    }

    private static byte[] validPdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private static String cookies(String sessionCookie, UUID guestSessionId) {
        List<String> values = new ArrayList<>();
        if (sessionCookie != null) {
            values.add(SESSION_COOKIE + "=" + sessionCookie);
        }
        if (guestSessionId != null) {
            values.add(GUEST_COOKIE + "=" + guestSessionId);
        }
        return String.join("; ", values);
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {

        @Bean
        @Primary
        FakeDocumentStorage fakeDocumentStorage() {
            return new FakeDocumentStorage();
        }
    }

    private record UserFixture(UUID id, String email) {
    }

    private record CsrfTicket(String sessionCookie, String token) {
    }

    private record AuthenticatedSession(String cookie, CsrfTicket csrf) {
    }
}
