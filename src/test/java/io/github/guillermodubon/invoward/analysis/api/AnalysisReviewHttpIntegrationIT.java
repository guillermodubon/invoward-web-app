package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJob;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisJobStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@AutoConfigureMockMvc
class AnalysisReviewHttpIntegrationIT {

    private static final String RAW_PASSWORD = "history review integration passphrase";
    private static final String REVIEW_PATH = "/api/analyses/%s/review-status";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";

    private final List<UUID> analysisIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> guestSessionIds = new ArrayList<>();
    private final List<MockHttpSession> authenticatedSessions = new ArrayList<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AnalysisRepository analysisRepository;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private SessionRegistry sessionRegistry;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void cleanFixtures() {
        authenticatedSessions.forEach(session -> sessionRegistry.removeSessionInformation(session.getId()));
        analysisIds.forEach(analysisId -> jdbcTemplate.update(
                "DELETE FROM invoward.generated_reports WHERE analysis_id = ?", analysisId));
        analysisIds.forEach(analysisId -> jdbcTemplate.update(
                "DELETE FROM invoward.analysis_notes WHERE analysis_id = ?", analysisId));
        analysisIds.forEach(analysisId -> jdbcTemplate.update(
                "DELETE FROM invoward.analysis_jobs WHERE analysis_id = ?", analysisId));
        analysisIds.forEach(analysisId -> jdbcTemplate.update(
                "DELETE FROM invoward.analyses WHERE id = ?", analysisId));
        guestSessionIds.forEach(guestSessionId -> jdbcTemplate.update(
                "DELETE FROM invoward.guest_sessions WHERE id = ?", guestSessionId));
        userIds.forEach(userId -> jdbcTemplate.update("DELETE FROM invoward.users WHERE id = ?", userId));
        authenticatedSessions.clear();
        analysisIds.clear();
        guestSessionIds.clear();
        userIds.clear();
    }

    @Test
    void reviewPatchEnforcesAuthenticationCsrfOwnershipAndCompletedState() throws Exception {
        UserFixture owner = createUser("review-owner");
        UserFixture otherUser = createUser("review-other");
        AnalysisFixture completed = createCompletedAnalysis(owner.id());
        AnalysisFixture incomplete = createCreatedAnalysis(owner.id());
        GuestFixture guest = createGuestAnalysis();

        MockHttpSession ownerSession = login(owner);
        CsrfTicket ownerCsrf = csrfTicket(ownerSession);
        MockHttpSession otherSession = login(otherUser);
        CsrfTicket otherCsrf = csrfTicket(otherSession);
        CsrfTicket anonymousCsrf = csrfTicket(null);

        String reviewPath = REVIEW_PATH.formatted(completed.analysisId());
        String requestBody = "{\"reviewStatus\":\"REVIEWED\"}";
        Cookie guestCookie = new Cookie(GUEST_COOKIE, guest.guestSessionId().toString());

        mockMvc.perform(patch(reviewPath)
                        .session(anonymousCsrf.session())
                        .header(anonymousCsrf.headerName(), anonymousCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(
                        "{\"code\":\"AUTHENTICATION_REQUIRED\","
                                + "\"message\":\"Authentication is required.\"}"));

        mockMvc.perform(patch(reviewPath)
                        .session(ownerSession)
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isForbidden())
                .andExpect(content().json(
                        "{\"code\":\"CSRF_INVALID\","
                                + "\"message\":\"The request could not be validated.\"}"));

        mockMvc.perform(patch(reviewPath)
                        .session(ownerSession)
                        .header(ownerCsrf.headerName(), "invalid-token")
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch(reviewPath)
                        .session(ownerSession)
                        .header(ownerCsrf.headerName(), ownerCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(
                        "{\"code\":\"INVALID_REQUEST\",\"message\":\"The request is invalid.\"}"));

        mockMvc.perform(patch(reviewPath)
                        .session(ownerSession)
                        .header(ownerCsrf.headerName(), ownerCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(
                        "{\"code\":\"INVALID_REQUEST\",\"message\":\"The request is invalid.\"}"));

        String foreignResponse = mockMvc.perform(patch(reviewPath)
                        .session(otherSession)
                        .header(otherCsrf.headerName(), otherCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(content().json(
                        "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}"))
                .andReturn().getResponse().getContentAsString();
        String missingResponse = mockMvc.perform(patch(REVIEW_PATH.formatted(UUID.randomUUID()))
                        .session(otherSession)
                        .header(otherCsrf.headerName(), otherCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertEquals(foreignResponse, missingResponse);

        mockMvc.perform(patch(REVIEW_PATH.formatted(guest.analysisId()))
                        .session(ownerSession)
                        .cookie(guestCookie)
                        .header(ownerCsrf.headerName(), ownerCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(content().json(
                        "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}"));

        mockMvc.perform(patch(REVIEW_PATH.formatted(incomplete.analysisId()))
                        .session(ownerSession)
                        .header(ownerCsrf.headerName(), ownerCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(content().json(
                        "{\"code\":\"ANALYSIS_REVIEW_NOT_ALLOWED\","
                                + "\"message\":\"Only completed analyses can be reviewed.\"}"));

        AnalysisJob jobBeforeReview = analysisJobRepository.findByAnalysisId(completed.analysisId()).orElseThrow();
        MvcResult reviewed = mockMvc.perform(patch(reviewPath)
                        .session(ownerSession)
                        .cookie(guestCookie)
                        .header(ownerCsrf.headerName(), ownerCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.analysisId").value(completed.analysisId().toString()))
                .andExpect(jsonPath("$.reviewStatus").value("REVIEWED"))
                .andExpect(jsonPath("$.reviewedAt").exists())
                .andReturn();
        JsonNode reviewedBody = objectMapper.readTree(reviewed.getResponse().getContentAsByteArray());
        String reviewedAt = reviewedBody.get("reviewedAt").asString();
        assertEquals(List.of("analysisId", "reviewStatus", "reviewedAt"), sortedPropertyNames(reviewedBody));

        MvcResult repeated = mockMvc.perform(patch(reviewPath)
                        .session(ownerSession)
                        .header(ownerCsrf.headerName(), ownerCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertEquals(reviewedAt, objectMapper.readTree(repeated.getResponse().getContentAsByteArray())
                .get("reviewedAt").asString());

        mockMvc.perform(get("/api/analyses/{analysisId}", completed.analysisId()).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REVIEWED"))
                .andExpect(jsonPath("$.reviewedAt").value(reviewedAt));

        mockMvc.perform(patch(reviewPath)
                        .session(ownerSession)
                        .header(ownerCsrf.headerName(), ownerCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"PENDING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.reviewedAt").value(org.hamcrest.Matchers.nullValue()));

        Analysis persisted = analysisRepository.findOwnedById(
                completed.analysisId(), new RegisteredUserOwner(owner.id()), Instant.now()).orElseThrow();
        AnalysisJob persistedJob = analysisJobRepository.findByAnalysisId(completed.analysisId()).orElseThrow();
        assertEquals(AnalysisStatus.COMPLETED, persisted.status());
        assertEquals(AnalysisReconciliationStatus.MATCHED, persisted.reconciliationStatus());
        assertEquals(AnalysisReviewStatus.PENDING, persisted.reviewStatus());
        assertNull(persisted.reviewedAt());
        assertEquals("Review Supplier", persisted.supplierName());
        assertEquals(new BigDecimal("5.0000"), persisted.difference());
        assertEquals(jobBeforeReview, persistedJob, "Review changes must not mutate processing state.");

        MvcResult guestDetail = mockMvc.perform(get("/api/analyses/{analysisId}", guest.analysisId())
                        .cookie(guestCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();
        assertFalse(guestDetail.getResponse().getContentAsString().contains(guest.guestSessionId().toString()));
    }

    private UserFixture createUser(String prefix) {
        UUID userId = UUID.randomUUID();
        String email = prefix + "-" + UUID.randomUUID() + "@example.test";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'History Review User', ?, ?, true, ?)
                """, userId, email, passwordEncoder.encode(RAW_PASSWORD), UserStatus.ACTIVE.name());
        userIds.add(userId);
        return new UserFixture(userId, email);
    }

    private AnalysisFixture createCompletedAnalysis(UUID userId) {
        RegisteredUserOwner owner = new RegisteredUserOwner(userId);
        Instant createdAt = Instant.now().minusSeconds(90);
        Instant completedAt = Instant.now().minusSeconds(30);
        Analysis analysis = new Analysis(
                UUID.randomUUID(), owner, AnalysisStatus.COMPLETED, AnalysisReviewStatus.PENDING, null,
                AnalysisReconciliationStatus.MATCHED, "Review Supplier", "review supplier", "QUOTE",
                "REF-REVIEW", "INV-REVIEW", "USD", new BigDecimal("75.0000"),
                new BigDecimal("80.0000"), new BigDecimal("5.0000"), PriceTolerance.exactMatch(),
                false, null, null, 0, completedAt, null, createdAt, completedAt);
        Analysis persisted = analysisRepository.create(analysis);
        analysisIds.add(persisted.id());

        Instant startedAt = completedAt.minusSeconds(10);
        AnalysisJob job = new AnalysisJob(
                UUID.randomUUID(), persisted.id(), AnalysisJobStatus.COMPLETED, AnalysisStatus.COMPLETED,
                2, false, null, null, startedAt, completedAt, createdAt, completedAt);
        AnalysisJob persistedJob = analysisJobRepository.create(job);
        return new AnalysisFixture(persisted.id(), persistedJob);
    }

    private AnalysisFixture createCreatedAnalysis(UUID userId) {
        Instant now = Instant.now().minusSeconds(30);
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), new RegisteredUserOwner(userId), PriceTolerance.exactMatch(), now));
        analysisIds.add(analysis.id());
        AnalysisJob job = analysisJobRepository.create(AnalysisJob.waitingForUser(
                UUID.randomUUID(), analysis.id(), now));
        return new AnalysisFixture(analysis.id(), job);
    }

    private GuestFixture createGuestAnalysis() throws Exception {
        UUID guestSessionId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
        guestSessionIds.add(guestSessionId);
        Instant expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.guest_sessions WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp("expires_at").toInstant(),
                guestSessionId);
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(), new GuestSessionOwner(guestSessionId, expiresAt),
                PriceTolerance.exactMatch(), Instant.now()));
        analysisIds.add(analysis.id());
        return new GuestFixture(analysis.id(), guestSessionId);
    }

    private MockHttpSession login(UserFixture user) throws Exception {
        CsrfTicket anonymousCsrf = csrfTicket(null);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(anonymousCsrf.session())
                        .header(anonymousCsrf.headerName(), anonymousCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + user.email() + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);
        authenticatedSessions.add(session);
        return session;
    }

    private CsrfTicket csrfTicket(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        MockHttpSession resultingSession = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(resultingSession);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new CsrfTicket(resultingSession, body.get("headerName").asString(), body.get("token").asString());
    }

    private static List<String> sortedPropertyNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(names::add);
        names.sort(String::compareTo);
        return names;
    }

    private record UserFixture(UUID id, String email) {
    }

    private record AnalysisFixture(UUID analysisId, AnalysisJob job) {
    }

    private record GuestFixture(UUID analysisId, UUID guestSessionId) {
    }

    private record CsrfTicket(MockHttpSession session, String headerName, String token) {
    }
}
