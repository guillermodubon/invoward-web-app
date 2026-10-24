package io.github.guillermodubon.invoward.analysis.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.api.model.PutAnalysisNoteRequest;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.analysis.domain.PriceTolerance;
import io.github.guillermodubon.invoward.analysis.domain.RegisteredUserOwner;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.support.database.DatabaseFixtures;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "invoward.email.provider=disabled")
@AutoConfigureMockMvc
class AnalysisNoteHttpIntegrationIT {

    private static final String RAW_PASSWORD = "history note integration passphrase";
    private static final String NOTE_PATH = "/api/analyses/%s/note";
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
    private SessionRegistry sessionRegistry;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void cleanFixtures() {
        authenticatedSessions.forEach(session -> sessionRegistry.removeSessionInformation(session.getId()));
        analysisIds.forEach(analysisId -> jdbcTemplate.update(
                "DELETE FROM invoward.analysis_notes WHERE analysis_id = ?", analysisId));
        analysisIds.forEach(analysisId -> jdbcTemplate.update(
                "DELETE FROM invoward.analyses WHERE id = ?", analysisId));
        guestSessionIds.forEach(guestSessionId -> jdbcTemplate.update(
                "DELETE FROM invoward.guest_sessions WHERE id = ?", guestSessionId));
        userIds.forEach(userId -> jdbcTemplate.update(
                "DELETE FROM invoward.users WHERE id = ?", userId));
        authenticatedSessions.clear();
        analysisIds.clear();
        guestSessionIds.clear();
        userIds.clear();
    }

    @Test
    void registeredSessionReadsCreatesReplacesAndClearsPrivateNoteWithCsrf() throws Exception {
        UserFixture owner = createUser("note-owner");
        AnalysisFixture analysis = createRegisteredAnalysis(owner.id());
        GuestFixture guest = createGuestAnalysis();
        MockHttpSession session = login(owner);
        CsrfTicket csrf = csrfTicket(session);
        String notePath = NOTE_PATH.formatted(analysis.analysisId());
        Cookie guestCookie = new Cookie(GUEST_COOKIE, guest.guestSessionId().toString());

        MvcResult emptyRead = mockMvc.perform(get(notePath).session(session).cookie(guestCookie))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertEmptyNote(objectMapper.readTree(emptyRead.getResponse().getContentAsByteArray()), analysis.analysisId());

        mockMvc.perform(put(notePath)
                        .session(session)
                        .cookie(guestCookie)
                        .contentType(APPLICATION_JSON)
                        .content("{\"text\":\"private note sentinel\",\"expectedVersion\":null}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json(
                        "{\"code\":\"CSRF_INVALID\",\"message\":\"The request could not be validated.\"}"));

        MvcResult created = mockMvc.perform(put(notePath)
                        .session(session)
                        .cookie(guestCookie)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"text\":\"private note sentinel\",\"expectedVersion\":null}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.analysisId").value(analysis.analysisId().toString()))
                .andExpect(jsonPath("$.text").value("private note sentinel"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsByteArray());
        assertTrue(createdBody.has("createdAt"));
        assertTrue(createdBody.has("updatedAt"));

        mockMvc.perform(get(notePath).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.text").value("private note sentinel"));

        String historyBody = mockMvc.perform(get("/api/analyses").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String detailBody = mockMvc.perform(get("/api/analyses/" + analysis.analysisId()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(historyBody.contains("private note sentinel"));
        assertFalse(detailBody.contains("private note sentinel"));

        mockMvc.perform(put(notePath)
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"text\":null,\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(
                        "{\"code\":\"NOTE_INVALID\",\"message\":\"The analysis note is invalid.\"}"));

        mockMvc.perform(put(notePath)
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(
                        "{\"code\":\"NOTE_INVALID\",\"message\":\"The analysis note is invalid.\"}"));

        mockMvc.perform(put(notePath)
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"text\":\"stale replacement\",\"expectedVersion\":42}"))
                .andExpect(status().isConflict())
                .andExpect(content().json(
                        "{\"code\":\"NOTE_VERSION_CONFLICT\","
                                + "\"message\":\"The analysis note changed. Refresh and try again.\"}"));

        mockMvc.perform(put(notePath)
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"text\":\"current replacement\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put(notePath)
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"text\":\"  \\n \",\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(content().json(
                        "{\"code\":\"NOTE_VERSION_CONFLICT\","
                                + "\"message\":\"The analysis note changed. Refresh and try again.\"}"));

        MvcResult cleared = mockMvc.perform(put(notePath)
                        .session(session)
                        .header(csrf.headerName(), csrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"text\":\"  \\n \",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertEmptyNote(objectMapper.readTree(cleared.getResponse().getContentAsByteArray()), analysis.analysisId());

        MvcResult absentRead = mockMvc.perform(get(notePath).session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertEmptyNote(objectMapper.readTree(absentRead.getResponse().getContentAsByteArray()), analysis.analysisId());

        MvcResult guestAnalysisRead = mockMvc.perform(get(NOTE_PATH.formatted(guest.analysisId()))
                        .session(session)
                        .cookie(guestCookie))
                .andExpect(status().isNotFound())
                .andExpect(content().json(
                        "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}"))
                .andReturn();
        assertFalse(guestAnalysisRead.getResponse().getContentAsString().contains(guest.guestSessionId().toString()));
    }

    @Test
    void foreignRegisteredOwnerCannotReadOrWriteNotesAndReceivesSameNotFoundAsMissingAnalysis()
            throws Exception {
        UserFixture ownerA = createUser("note-owner-a");
        UserFixture ownerB = createUser("note-owner-b");
        AnalysisFixture analysisA = createRegisteredAnalysis(ownerA.id());
        MockHttpSession sessionA = login(ownerA);
        CsrfTicket csrfA = csrfTicket(sessionA);
        MockHttpSession sessionB = login(ownerB);
        CsrfTicket csrfB = csrfTicket(sessionB);
        String foreignPath = NOTE_PATH.formatted(analysisA.analysisId());
        String privateNote = "private-note-log-sentinel-" + UUID.randomUUID();
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> capturedLogs = new ListAppender<>();
        capturedLogs.setContext(rootLogger.getLoggerContext());
        capturedLogs.start();
        rootLogger.addAppender(capturedLogs);

        try {
            mockMvc.perform(put(foreignPath)
                            .session(sessionA)
                            .header(csrfA.headerName(), csrfA.token())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new PutAnalysisNoteRequest(privateNote, null))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.text").value(privateNote));

            String foreignRead = mockMvc.perform(get(foreignPath).session(sessionB))
                    .andExpect(status().isNotFound())
                    .andExpect(content().json(
                            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}"))
                    .andReturn().getResponse().getContentAsString();
            String missingRead = mockMvc.perform(get(NOTE_PATH.formatted(UUID.randomUUID())).session(sessionB))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertEquals(missingRead, foreignRead);

            mockMvc.perform(put(foreignPath)
                            .session(sessionB)
                            .header(csrfB.headerName(), csrfB.token())
                            .contentType(APPLICATION_JSON)
                            .content("{\"text\":\"foreign write\",\"expectedVersion\":null}"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().json(
                            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}"));

            String foreignDetail = mockMvc.perform(get("/api/analyses/{id}", analysisA.analysisId())
                            .session(sessionB))
                    .andExpect(status().isNotFound())
                    .andExpect(content().json(
                            "{\"code\":\"ANALYSIS_NOT_FOUND\",\"message\":\"Analysis was not found.\"}"))
                    .andReturn().getResponse().getContentAsString();
            String missingDetail = mockMvc.perform(get("/api/analyses/{id}", UUID.randomUUID())
                            .session(sessionB))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertEquals(missingDetail, foreignDetail);
        } finally {
            rootLogger.detachAppender(capturedLogs);
            capturedLogs.stop();
        }

        assertTrue(capturedLogs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(privateNote)),
                "Private note contents must not be written to application logs.");
    }

    @Test
    void registeredHistoryPageAndCountsAreIsolatedFromOtherUsersAndGuestRows() throws Exception {
        UserFixture ownerA = createUser("history-owner-a");
        UserFixture ownerB = createUser("history-owner-b");
        AnalysisFixture ownerAOlder = createRegisteredAnalysis(ownerA.id());
        AnalysisFixture ownerANewer = createRegisteredAnalysis(ownerA.id());
        AnalysisFixture ownerBOlder = createRegisteredAnalysis(ownerB.id());
        AnalysisFixture ownerBNewer = createRegisteredAnalysis(ownerB.id());
        GuestFixture guest = createGuestAnalysis();
        Instant now = Instant.now();
        applyHistoryFields(ownerAOlder.analysisId(), now.minusSeconds(40));
        applyHistoryFields(ownerANewer.analysisId(), now.minusSeconds(30));
        applyHistoryFields(ownerBOlder.analysisId(), now.minusSeconds(20));
        applyHistoryFields(ownerBNewer.analysisId(), now.minusSeconds(10));
        applyHistoryFields(guest.analysisId(), now.minusSeconds(5));

        MockHttpSession sessionA = login(ownerA);
        MockHttpSession sessionB = login(ownerB);

        mockMvc.perform(get("/api/analyses")
                        .param("search", "shared-history-record")
                        .param("supplier", "shared history supplier")
                        .param("processingStatus", "CREATED")
                        .param("sort", "createdAt,desc")
                        .param("page", "0")
                        .param("size", "1")
                        .param("userId", ownerB.id().toString())
                        .session(sessionA))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.hasPrevious").value(false))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ownerANewer.analysisId().toString()))
                .andExpect(jsonPath("$.content[0].supplierName").value("Shared History Supplier"))
                .andExpect(jsonPath("$.content[0].invoiceNumber").value("SHARED-HISTORY-RECORD"))
                .andExpect(jsonPath("$.content[0].userId").doesNotExist())
                .andExpect(jsonPath("$.content[0].guestSessionId").doesNotExist())
                .andExpect(jsonPath("$.content[0].note").doesNotExist());

        mockMvc.perform(get("/api/analyses")
                        .param("search", "shared-history-record")
                        .param("supplier", "shared history supplier")
                        .param("processingStatus", "CREATED")
                        .param("sort", "createdAt,desc")
                        .param("page", "1")
                        .param("size", "1")
                        .session(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(true))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ownerAOlder.analysisId().toString()));

        mockMvc.perform(get("/api/analyses")
                        .param("search", "shared-history-record")
                        .param("supplier", "shared history supplier")
                        .param("processingStatus", "CREATED")
                        .param("sort", "createdAt,desc")
                        .param("size", "10")
                        .session(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(ownerBNewer.analysisId().toString()))
                .andExpect(jsonPath("$.content[1].id").value(ownerBOlder.analysisId().toString()));

        mockMvc.perform(get("/api/analyses")
                        .param("search", "shared-history-record")
                        .param("supplier", "shared history supplier")
                        .param("processingStatus", "CREATED")
                        .param("userId", ownerA.id().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(
                        "{\"code\":\"AUTHENTICATION_REQUIRED\","
                                + "\"message\":\"Authentication is required.\"}"));
    }

    @Test
    void guestCookiesCannotEnumerateRegisteredHistoryAndAuthenticatedIdentityRemainsAuthoritative()
            throws Exception {
        UserFixture owner = createUser("history-cookie-owner");
        UserFixture otherUser = createUser("history-cookie-other-user");
        AnalysisFixture registeredAnalysis = createRegisteredAnalysis(owner.id());
        GuestFixture foreignGuest = createGuestAnalysis();
        GuestFixture expiredGuest = createGuestAnalysis();
        applyHistoryFields(registeredAnalysis.analysisId(), Instant.now().minusSeconds(30));
        applyHistoryFields(foreignGuest.analysisId(), Instant.now().minusSeconds(20));
        applyHistoryFields(expiredGuest.analysisId(), Instant.now().minusSeconds(10));
        jdbcTemplate.update("""
                UPDATE invoward.guest_sessions
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    last_seen_at = CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE id = ?
                """, expiredGuest.guestSessionId());

        List<String> guestCookieValues = List.of(
                foreignGuest.guestSessionId().toString(),
                expiredGuest.guestSessionId().toString(),
                UUID.randomUUID().toString(),
                "malformed-guest-cookie");
        int guestSessionsBeforeRequests = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.guest_sessions", Integer.class);
        Instant foreignGuestLastSeen = guestLastSeen(foreignGuest.guestSessionId());
        Instant expiredGuestLastSeen = guestLastSeen(expiredGuest.guestSessionId());
        MockHttpSession ownerSession = login(owner);
        CsrfTicket anonymousCsrf = csrfTicket(null);

        for (String guestCookieValue : guestCookieValues) {
            Cookie guestCookie = new Cookie(GUEST_COOKIE, guestCookieValue);
            mockMvc.perform(get("/api/analyses")
                            .param("search", "shared-history-record")
                            .cookie(guestCookie))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json(
                            "{\"code\":\"AUTHENTICATION_REQUIRED\","
                                    + "\"message\":\"Authentication is required.\"}"));

            mockMvc.perform(get("/api/analyses")
                            .param("search", "shared-history-record")
                            .param("userId", otherUser.id().toString())
                            .cookie(guestCookie)
                            .session(ownerSession))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(registeredAnalysis.analysisId().toString()))
                    .andExpect(jsonPath("$.content[0].guestSessionId").doesNotExist());

            assertFalse(mockMvc.perform(get(NOTE_PATH.formatted(registeredAnalysis.analysisId()))
                            .cookie(guestCookie))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getHeaders("Set-Cookie").stream()
                    .anyMatch(value -> value.startsWith(GUEST_COOKIE + "=")),
                    "Account-only guest requests must not issue a new guest cookie.");
        }

        mockMvc.perform(put(NOTE_PATH.formatted(registeredAnalysis.analysisId()))
                        .session(ownerSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"text\":\"csrf required\",\"expectedVersion\":null}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json(
                        "{\"code\":\"CSRF_INVALID\",\"message\":\"The request could not be validated.\"}"));
        mockMvc.perform(patch("/api/analyses/{id}/review-status", registeredAnalysis.analysisId())
                        .session(ownerSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"REVIEWED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json(
                        "{\"code\":\"CSRF_INVALID\",\"message\":\"The request could not be validated.\"}"));

        mockMvc.perform(patch("/api/analyses/{id}/review-status", registeredAnalysis.analysisId())
                        .session(anonymousCsrf.session())
                        .cookie(new Cookie(GUEST_COOKIE, foreignGuest.guestSessionId().toString()))
                        .header(anonymousCsrf.headerName(), anonymousCsrf.token())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reviewStatus\":\"REVIEWED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(
                        "{\"code\":\"AUTHENTICATION_REQUIRED\","
                                + "\"message\":\"Authentication is required.\"}"));

        assertEquals(guestSessionsBeforeRequests, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.guest_sessions", Integer.class));
        assertEquals(foreignGuestLastSeen, guestLastSeen(foreignGuest.guestSessionId()));
        assertEquals(expiredGuestLastSeen, guestLastSeen(expiredGuest.guestSessionId()));
    }

    private void applyHistoryFields(UUID analysisId, Instant createdAt) {
        jdbcTemplate.update("""
                UPDATE invoward.analyses
                SET supplier_name = ?, invoice_number = ?, reference_number = ?, created_at = ?
                WHERE id = ?
                """, "Shared History Supplier", "SHARED-HISTORY-RECORD", "SHARED-HISTORY-REFERENCE",
                java.sql.Timestamp.from(createdAt), analysisId);
    }

    private Instant guestLastSeen(UUID guestSessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_seen_at FROM invoward.guest_sessions WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp("last_seen_at").toInstant(),
                guestSessionId);
    }

    @Test
    void anonymousAndGuestCookieRequestsCannotUseRegisteredNoteEndpoints() throws Exception {
        GuestFixture guest = createGuestAnalysis();
        String path = NOTE_PATH.formatted(guest.analysisId());

        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(
                        "{\"code\":\"AUTHENTICATION_REQUIRED\","
                                + "\"message\":\"Authentication is required.\"}"));

        mockMvc.perform(get(path).cookie(new Cookie(GUEST_COOKIE, guest.guestSessionId().toString())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(
                        "{\"code\":\"AUTHENTICATION_REQUIRED\","
                                + "\"message\":\"Authentication is required.\"}"));
    }

    private UserFixture createUser(String prefix) {
        UUID userId = UUID.randomUUID();
        String email = prefix + "-" + UUID.randomUUID() + "@example.test";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'History Note User', ?, ?, true, ?)
                """, userId, email, passwordEncoder.encode(RAW_PASSWORD), UserStatus.ACTIVE.name());
        userIds.add(userId);
        return new UserFixture(userId, email);
    }

    private AnalysisFixture createRegisteredAnalysis(UUID userId) {
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(),
                new RegisteredUserOwner(userId),
                PriceTolerance.exactMatch(),
                Instant.now()));
        analysisIds.add(analysis.id());
        return new AnalysisFixture(analysis.id());
    }

    private GuestFixture createGuestAnalysis() throws Exception {
        UUID guestSessionId = jdbcTemplate.execute((ConnectionCallback<UUID>) DatabaseFixtures::insertGuestSession);
        guestSessionIds.add(guestSessionId);
        Instant expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.guest_sessions WHERE id = ?",
                (resultSet, rowNumber) -> resultSet.getTimestamp("expires_at").toInstant(),
                guestSessionId);
        Analysis analysis = analysisRepository.create(Analysis.create(
                UUID.randomUUID(),
                new GuestSessionOwner(guestSessionId, expiresAt),
                PriceTolerance.exactMatch(),
                Instant.now()));
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
        assertNotNull(session, "Successful login must retain its server-side session.");
        authenticatedSessions.add(session);
        return session;
    }

    private CsrfTicket csrfTicket(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession resultingSession = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(resultingSession);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return new CsrfTicket(
                resultingSession,
                body.get("headerName").asString(),
                body.get("token").asString());
    }

    private static void assertEmptyNote(JsonNode body, UUID analysisId) {
        assertEquals(analysisId.toString(), body.get("analysisId").asString());
        assertTrue(body.has("text"));
        assertTrue(body.has("version"));
        assertTrue(body.has("createdAt"));
        assertTrue(body.has("updatedAt"));
        assertTrue(body.get("text").isNull());
        assertTrue(body.get("version").isNull());
        assertTrue(body.get("createdAt").isNull());
        assertTrue(body.get("updatedAt").isNull());
    }

    private record UserFixture(UUID id, String email) {
    }

    private record AnalysisFixture(UUID analysisId) {
    }

    private record GuestFixture(UUID analysisId, UUID guestSessionId) {
    }

    private record CsrfTicket(MockHttpSession session, String headerName, String token) {
    }
}
