package io.github.guillermodubon.invoward.document.api;

import io.github.guillermodubon.invoward.analysis.application.service.CreateAnalysisService;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.identity.application.model.GuestSession;
import io.github.guillermodubon.invoward.identity.application.service.GuestSessionService;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeDocumentStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "invoward.email.provider=disabled",
                "invoward.guest.session-ttl=24h",
                "invoward.document-storage.download-url-ttl=15m"
        })
@Import(DocumentGuestOwnershipIntegrationIT.TestStorageConfiguration.class)
class DocumentGuestOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String ANALYSIS_NOT_FOUND =
            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}";
    private static final String CSRF_INVALID =
            "{\"code\":\"CSRF_INVALID\",\"message\":\"The request could not be validated.\"}";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

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
    private GuestSessionService guestSessionService;

    @Autowired
    private CreateAnalysisService createAnalysisService;

    @Autowired
    private FakeDocumentStorage storage;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @BeforeEach
    void clearFakeStorage() {
        storage.clear();
    }

    @Test
    void guestCanUploadListAndGetDocumentWithCsrfAndFixedExpiryBoundedDownload() throws Exception {
        GuestAnalysis guest = createGuestAnalysis();
        Instant fixedExpiry = Instant.now().plus(Duration.ofMinutes(10));
        Instant oldActivity = Instant.now().minusSeconds(30);
        setGuestTimes(guest.guestSessionId(), guest.analysisId(), oldActivity, fixedExpiry);
        GuestRow initial = guestRow(guest.guestSessionId());
        fixedExpiry = initial.expiresAt();
        guest = new GuestAnalysis(guest.analysisId(), guest.guestSessionId(), fixedExpiry);
        assertEquals(fixedExpiry, initial.expiresAt());

        CsrfTicket csrf = csrfTicket(null);
        byte[] pdf = validPdf();
        HttpResponse<String> csrfRejected = upload(
                guest.analysisId(), csrf, guest.guestSessionId().toString(), null, pdf);
        assertEquals(403, csrfRejected.statusCode());
        assertEquals(CSRF_INVALID, csrfRejected.body());
        assertEquals(0, storage.storedObjectCount());

        HttpResponse<String> uploaded = upload(
                guest.analysisId(), csrf, guest.guestSessionId().toString(), csrf.token(), pdf);
        assertEquals(201, uploaded.statusCode());
        JsonNode uploadedDocument = objectMapper.readTree(uploaded.body());
        UUID documentId = UUID.fromString(uploadedDocument.get("id").asString());
        assertEquals("REFERENCE", uploadedDocument.get("role").asString());
        assertEquals("application/pdf", uploadedDocument.get("contentType").asString());
        assertEquals(pdf.length, uploadedDocument.get("sizeBytes").asLong());
        assertEquals("/api/analyses/" + guest.analysisId() + "/documents/" + documentId,
                uploaded.headers().firstValue("Location").orElseThrow());
        assertFalse(uploaded.body().contains(guest.guestSessionId().toString()));
        assertEquals(1, storage.storedObjectCount());
        assertSuccessfulActivityWithoutSlidingExpiry(guest, initial.createdAt());

        setLastSeen(guest.guestSessionId(), initial.createdAt());
        HttpResponse<String> listed = get(
                "/api/analyses/" + guest.analysisId() + "/documents", csrf.sessionCookie(),
                guest.guestSessionId().toString());
        assertEquals(200, listed.statusCode());
        JsonNode documents = objectMapper.readTree(listed.body());
        assertEquals(1, documents.size());
        assertEquals(documentId.toString(), documents.get(0).get("id").asString());
        assertFalse(listed.body().contains(guest.guestSessionId().toString()));
        assertSuccessfulActivityWithoutSlidingExpiry(guest, initial.createdAt());

        setLastSeen(guest.guestSessionId(), initial.createdAt());
        HttpResponse<String> detail = get(
                "/api/analyses/" + guest.analysisId() + "/documents/" + documentId,
                csrf.sessionCookie(), guest.guestSessionId().toString());
        assertEquals(200, detail.statusCode());
        JsonNode response = objectMapper.readTree(detail.body());
        Instant urlExpiry = Instant.parse(response.get("downloadUrlExpiresAt").asString());
        Instant documentExpiry = Instant.parse(response.get("expiresAt").asString());
        assertEquals(fixedExpiry, documentExpiry);
        assertTrue(urlExpiry.isAfter(Instant.now()));
        assertFalse(urlExpiry.isAfter(documentExpiry));
        assertTrue(Duration.between(Instant.now(), urlExpiry).compareTo(Duration.ofMinutes(15)) < 0);
        assertTrue(response.get("downloadUrl").asString().startsWith("https://fake-storage.invalid/"));
        assertFalse(detail.body().contains(guest.guestSessionId().toString()));
        assertSuccessfulActivityWithoutSlidingExpiry(guest, initial.createdAt());
    }

    @Test
    void foreignMissingMalformedUnknownAndExpiredGuestCookiesReturnSameNotFoundWithoutCreation()
            throws Exception {
        GuestAnalysis owner = createGuestAnalysis();
        CsrfTicket csrf = csrfTicket(null);
        HttpResponse<String> upload = upload(
                owner.analysisId(), csrf, owner.guestSessionId().toString(), csrf.token(), validPdf());
        assertEquals(201, upload.statusCode());
        UUID documentId = UUID.fromString(objectMapper.readTree(upload.body()).get("id").asString());

        GuestSession foreignGuest = guestSessionService.create();
        GuestSession expiredGuest = insertExpiredGuestSession();
        GuestRow ownerBefore = guestRow(owner.guestSessionId());
        GuestRow foreignBefore = guestRow(foreignGuest.id());
        GuestRow expiredBefore = guestRow(expiredGuest.id());
        long sessionsBefore = guestSessionCount();

        List<HttpResponse<String>> responses = new ArrayList<>();
        String collection = "/api/analyses/" + owner.analysisId() + "/documents";
        String detail = collection + "/" + documentId;
        for (String invalidOrForeignCookie : List.of(
                foreignGuest.id().toString(),
                "not-a-uuid",
                UUID.randomUUID().toString(),
                expiredGuest.id().toString())) {
            responses.add(get(collection, null, invalidOrForeignCookie));
            responses.add(get(detail, null, invalidOrForeignCookie));
        }
        responses.add(get(collection, null, null));
        responses.add(get(detail, null, null));

        for (HttpResponse<String> response : responses) {
            assertEquals(404, response.statusCode());
            assertEquals("application/json", mediaType(response));
            assertEquals(ANALYSIS_NOT_FOUND, response.body());
            assertFalse(response.body().contains(owner.guestSessionId().toString()));
            assertFalse(response.body().contains(foreignGuest.id().toString()));
        }

        List<String> invalidGuestCookies = new ArrayList<>(List.of(
                foreignGuest.id().toString(),
                "not-a-uuid",
                UUID.randomUUID().toString(),
                expiredGuest.id().toString()));
        invalidGuestCookies.add(null);
        CsrfTicket writeCsrf = csrfTicket(null);
        byte[] rejectedUpload = validPdf();
        for (String invalidGuestCookie : invalidGuestCookies) {
            HttpResponse<String> rejected = upload(
                    owner.analysisId(), writeCsrf, invalidGuestCookie, writeCsrf.token(), rejectedUpload);
            assertEquals(404, rejected.statusCode());
            assertEquals("application/json", mediaType(rejected));
            assertEquals(ANALYSIS_NOT_FOUND, rejected.body());
        }
        assertEquals(sessionsBefore, guestSessionCount(),
                "Nested document routes must never create GuestSession rows.");
        assertEquals(ownerBefore, guestRow(owner.guestSessionId()));
        assertEquals(foreignBefore, guestRow(foreignGuest.id()));
        assertEquals(expiredBefore, guestRow(expiredGuest.id()));
        assertEquals(1, storage.storedObjectCount(),
                "Rejected owners must not reach object storage.");
    }

    private void assertSuccessfulActivityWithoutSlidingExpiry(GuestAnalysis guest, Instant oldActivity) {
        GuestRow after = guestRow(guest.guestSessionId());
        assertTrue(after.lastSeenAt().isAfter(oldActivity));
        assertEquals(guest.fixedExpiry(), after.expiresAt());
        assertEquals(guest.fixedExpiry(), analysisExpiry(guest.analysisId()));
    }

    private GuestAnalysis createGuestAnalysis() {
        GuestSession guest = guestSessionService.create();
        var analysis = createAnalysisService.create(
                new GuestSessionOwner(guest.id(), guest.expiresAt()), PriceTolerance.exactMatch());
        return new GuestAnalysis(analysis.id(), guest.id(), guest.expiresAt());
    }

    private HttpResponse<String> upload(
            UUID analysisId, CsrfTicket csrf, String guestCookie, String csrfToken, byte[] pdf)
            throws Exception {
        String boundary = "InvoWardBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"role\"\r\n\r\n"
                + "REFERENCE\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"guest-reference.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(pdf);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));

        HttpRequest.Builder request = HttpRequest.newBuilder(applicationUri().resolve(
                        "/api/analyses/" + analysisId + "/documents"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Cookie", cookies(csrf.sessionCookie(), guestCookie));
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return httpClient.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private CsrfTicket csrfTicket(String existingSessionCookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(applicationUri().resolve("/api/auth/csrf"))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (existingSessionCookie != null) {
            request.header("Cookie", SESSION_COOKIE + "=" + existingSessionCookie);
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        return new CsrfTicket(cookieValue(response, SESSION_COOKIE), body.get("token").asString());
    }

    private HttpResponse<String> get(String path, String sessionCookie, String guestCookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(applicationUri().resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        String cookieHeader = cookies(sessionCookie, guestCookie);
        if (!cookieHeader.isBlank()) {
            request.header("Cookie", cookieHeader);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private GuestSession insertExpiredGuestSession() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(Duration.ofHours(48));
        Instant lastSeenAt = createdAt.plus(Duration.ofHours(1));
        Instant expiresAt = createdAt.plus(Duration.ofHours(24));
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, id, Timestamp.from(expiresAt), Timestamp.from(lastSeenAt), Timestamp.from(createdAt));
        return new GuestSession(id, expiresAt, lastSeenAt, createdAt);
    }

    private void setGuestTimes(UUID guestId, UUID analysisId, Instant createdAt, Instant expiresAt) {
        jdbcTemplate.update("""
                UPDATE invoward.guest_sessions
                SET created_at = ?, last_seen_at = ?, expires_at = ?
                WHERE id = ?
                """, Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(expiresAt), guestId);
        jdbcTemplate.update("UPDATE invoward.analyses SET expires_at = ? WHERE id = ?",
                Timestamp.from(expiresAt), analysisId);
    }

    private void setLastSeen(UUID guestId, Instant lastSeenAt) {
        jdbcTemplate.update("UPDATE invoward.guest_sessions SET last_seen_at = ? WHERE id = ?",
                Timestamp.from(lastSeenAt), guestId);
    }

    private GuestRow guestRow(UUID guestId) {
        return jdbcTemplate.queryForObject("""
                SELECT created_at, last_seen_at, expires_at
                FROM invoward.guest_sessions WHERE id = ?
                """, (result, rowNumber) -> new GuestRow(
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("last_seen_at").toInstant(),
                result.getTimestamp("expires_at").toInstant()), guestId);
    }

    private Instant analysisExpiry(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.analyses WHERE id = ?",
                (result, rowNumber) -> result.getTimestamp(1).toInstant(), analysisId);
    }

    private long guestSessionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoward.guest_sessions", Long.class);
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

    private static String cookieValue(HttpResponse<?> response, String name) {
        String prefix = name + "=";
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected session cookie."));
    }

    private static String mediaType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElseThrow().split(";", 2)[0];
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestStorageConfiguration {

        @Bean
        @Primary
        FakeDocumentStorage fakeDocumentStorage() {
            return new FakeDocumentStorage();
        }
    }

    private record CsrfTicket(String sessionCookie, String token) {
    }

    private record GuestAnalysis(UUID analysisId, UUID guestSessionId, Instant fixedExpiry) {
    }

    private record GuestRow(Instant createdAt, Instant lastSeenAt, Instant expiresAt) {
    }
}
