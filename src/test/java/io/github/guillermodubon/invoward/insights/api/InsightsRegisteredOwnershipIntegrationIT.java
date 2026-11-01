package io.github.guillermodubon.invoward.insights.api;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisProcessingSignalPublisher;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import io.github.guillermodubon.invoward.processing.infrastructure.ProcessingWorker;
import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import io.github.guillermodubon.invoward.reconciliation.application.service.ReconcileAnalysisService;
import io.github.guillermodubon.invoward.reconciliation.application.service.RunLineItemMatchingService;
import io.github.guillermodubon.invoward.reporting.application.port.ReportGenerator;
import io.github.guillermodubon.invoward.reporting.application.port.ReportStorage;
import io.github.guillermodubon.invoward.reporting.application.service.GenerateReportService;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/** Verifies Insights ownership and privacy over real HTTP sessions and PostgreSQL. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"invoward.email.provider=disabled", "spring.ai.model.chat=none"})
class InsightsRegisteredOwnershipIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final String RAW_PASSWORD = "insights ownership integration passphrase";
    private static final String SHARED_SUPPLIER = "shared-supplier";
    private static final String A_ONLY_SUPPLIER = "a-only-supplier";
    private static final String B_ONLY_SUPPLIER = "b-only-supplier";
    private static final String GUEST_ONLY_SUPPLIER = "guest-only-supplier";
    private static final String PRIVATE_NOTE_SENTINEL = "PRIVATE_NOTE_SENTINEL_6f77";
    private static final String PRIVATE_SHARE_TOKEN_SENTINEL = "PRIVATE_SHARE_TOKEN_SENTINEL_8b12";
    private static final String PRIVATE_STORAGE_KEY_SENTINEL = "private-storage-key-sentinel.pdf";
    private static final String PRIVATE_REPORT_URL_SENTINEL = "https://reports.invalid/private-report-sentinel";
    private static final String PRIVATE_AI_TEXT_SENTINEL = "PRIVATE_AI_TEXT_SENTINEL_74a1";
    private static final String SUPPLIER_NOT_FOUND =
            "{\"code\":\"SUPPLIER_NOT_FOUND\",\"message\":\"Supplier was not found.\"}";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private UUID guestSessionIdMarker;

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

    @MockitoSpyBean
    private AnalysisRepository analysisRepository;

    @MockitoSpyBean
    private AnalysisJobRepository analysisJobRepository;

    @MockitoSpyBean
    private AnalysisProcessingSignalPublisher processingSignalPublisher;

    @MockitoSpyBean
    private ProcessingWorker processingWorker;

    @MockitoSpyBean
    private DocumentIntelligence documentIntelligence;

    @MockitoSpyBean
    private AmbiguousLineMatcher ambiguousLineMatcher;

    @MockitoSpyBean
    private RunLineItemMatchingService runLineItemMatchingService;

    @MockitoSpyBean
    private ReconcileAnalysisService reconcileAnalysisService;

    @MockitoSpyBean
    private GenerateReportService generateReportService;

    @MockitoSpyBean
    private ReportGenerator reportGenerator;

    @MockitoSpyBean
    private ReportStorage reportStorage;

    @MockitoSpyBean
    private DocumentStorage documentStorage;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @Test
    void registeredSessionsSeeOnlyTheirOwnDashboardAndSupplierInsights() throws Exception {
        UserFixture userA = insertActiveVerifiedUser("insights-a");
        UserFixture userB = insertActiveVerifiedUser("insights-b");
        Instant completedBase = currentMonthFixtureTime();

        insertAnalysis(
                userA.id(), null, "MATCHED", "A Shared Vendor", SHARED_SUPPLIER,
                "A-REF-1", "A-SHARED-MATCHED", "USD", "100.0000", "100.0000", "0.0000",
                completedBase.minusSeconds(50));
        AnalysisFixture aSharedReview = insertAnalysis(
                userA.id(), null, "REVIEW_REQUIRED", "A Shared Vendor", SHARED_SUPPLIER,
                "A-REF-2", "A-SHARED-REVIEW", "USD", "100.0000", "120.0000", "20.0000",
                completedBase.minusSeconds(30));
        insertPrivateAnalysisData(aSharedReview, completedBase);
        insertAnalysis(userA.id(), null, "REVIEW_REQUIRED", "A Exclusive Vendor", A_ONLY_SUPPLIER,
                "A-REF-3", "A-ONLY-REVIEW", "EUR", "50.0000", "55.0000", "5.0000",
                completedBase.minusSeconds(10));

        AnalysisFixture bSharedReview = insertAnalysis(
                userB.id(), null, "REVIEW_REQUIRED", "B Shared Vendor", SHARED_SUPPLIER,
                "B-REF-1", "B-SHARED-REVIEW", "USD", "1000.0000", "1200.0000", "200.0000",
                completedBase.minusSeconds(40));
        insertAnalysis(userB.id(), null, "MATCHED", "B Exclusive Vendor", B_ONLY_SUPPLIER,
                "B-REF-2", "B-ONLY-MATCHED", "EUR", "500.0000", "500.0000", "0.0000",
                completedBase.minusSeconds(5));

        insertDiscrepancy(aSharedReview.id(), "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(aSharedReview.id(), "TAX_MISMATCH", PRIVATE_AI_TEXT_SENTINEL);
        insertDiscrepancy(bSharedReview.id(), "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(bSharedReview.id(), "UNIT_PRICE_MISMATCH");

        GuestFixture guest = insertActiveGuest();
        insertAnalysis(null, guest.id(), "REVIEW_REQUIRED", "Guest Exclusive Vendor", GUEST_ONLY_SUPPLIER,
                "G-REF-1", "GUEST-ONLY-INVOICE", "USD", "9999.0000", "9999.0000", "0.0000",
                completedBase.minusSeconds(1));

        URI baseUri = applicationUri();
        AuthenticatedSession sessionA = login(baseUri, userA);
        AuthenticatedSession sessionB = login(baseUri, userB);

        HttpResponse<String> anonymous = get(baseUri, "/api/dashboard/summary", null, null);
        assertEquals(401, anonymous.statusCode());
        assertEquals("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication is required.\"}",
                anonymous.body());
        assertTrue(anonymous.headers().allValues("Set-Cookie").isEmpty());

        String summaryPath = dashboardSummaryPath();
        HttpResponse<String> dashboardA = get(baseUri, summaryPath, sessionA, guest.id());
        HttpResponse<String> dashboardB = get(baseUri, summaryPath, sessionB, guest.id());
        assertSummaryOwner(dashboardA, userA, userB,
                Map.of(SHARED_SUPPLIER, "A Shared Vendor", A_ONLY_SUPPLIER, "A Exclusive Vendor"),
                3, 1, "USD", "220.0000", "EUR", "55.0000");
        assertSummaryOwner(dashboardB, userB, userA,
                Map.of(SHARED_SUPPLIER, "B Shared Vendor", B_ONLY_SUPPLIER, "B Exclusive Vendor"),
                2, 1, "USD", "1200.0000", "EUR", "500.0000");
        assertEquals(1, discrepancyCount(dashboardA, "UNIT_PRICE_MISMATCH"));
        assertEquals(1, discrepancyCount(dashboardA, "TAX_MISMATCH"));
        assertEquals(2, discrepancyCount(dashboardB, "UNIT_PRICE_MISMATCH"));
        assertEquals(0, discrepancyCount(dashboardB, "TAX_MISMATCH"));

        HttpResponse<String> recentA = get(baseUri,
                "/api/dashboard/recent?view=RECENT&page=0&size=10", sessionA, guest.id());
        HttpResponse<String> recentB = get(baseUri,
                "/api/dashboard/recent?view=RECENT&page=0&size=10", sessionB, guest.id());
        assertRecentInvoices(recentA, userA, userB,
                Set.of("A-SHARED-MATCHED", "A-SHARED-REVIEW", "A-ONLY-REVIEW"));
        assertRecentInvoices(recentB, userB, userA, Set.of("B-SHARED-REVIEW", "B-ONLY-MATCHED"));

        HttpResponse<String> attentionA = get(baseUri,
                "/api/dashboard/recent?view=NEEDS_ATTENTION&page=0&size=10", sessionA, guest.id());
        HttpResponse<String> attentionB = get(baseUri,
                "/api/dashboard/recent?view=NEEDS_ATTENTION&page=0&size=10", sessionB, guest.id());
        assertRecentInvoices(attentionA, userA, userB, Set.of("A-SHARED-REVIEW", "A-ONLY-REVIEW"));
        assertRecentInvoices(attentionB, userB, userA, Set.of("B-SHARED-REVIEW"));

        HttpResponse<String> supplierListA = get(baseUri, "/api/suppliers?page=0&size=20", sessionA, guest.id());
        HttpResponse<String> supplierListB = get(baseUri, "/api/suppliers?page=0&size=20", sessionB, guest.id());
        assertSupplierKeys(supplierListA, userA, userB,
                Map.of(SHARED_SUPPLIER, "A Shared Vendor", A_ONLY_SUPPLIER, "A Exclusive Vendor"));
        assertSupplierKeys(supplierListB, userB, userA,
                Map.of(SHARED_SUPPLIER, "B Shared Vendor", B_ONLY_SUPPLIER, "B Exclusive Vendor"));

        HttpResponse<String> sharedSummaryA = get(
                baseUri, "/api/suppliers/" + SHARED_SUPPLIER + "/summary", sessionA, guest.id());
        HttpResponse<String> sharedSummaryB = get(
                baseUri, "/api/suppliers/" + SHARED_SUPPLIER + "/summary", sessionB, guest.id());
        assertSupplierSummary(sharedSummaryA, userA, userB, "A Shared Vendor", 2, 1,
                "220.0000", 1, Set.of("A-SHARED-MATCHED", "A-SHARED-REVIEW"));
        assertSupplierSummary(sharedSummaryB, userB, userA, "B Shared Vendor", 1, 0,
                "1200.0000", 2, Set.of("B-SHARED-REVIEW"));

        HttpResponse<String> sharedAnalysesA = get(
                baseUri, "/api/suppliers/" + SHARED_SUPPLIER + "/analyses", sessionA, guest.id());
        HttpResponse<String> sharedAnalysesB = get(
                baseUri, "/api/suppliers/" + SHARED_SUPPLIER + "/analyses", sessionB, guest.id());
        assertSupplierAnalyses(sharedAnalysesA, userA, userB,
                Set.of("A-SHARED-MATCHED", "A-SHARED-REVIEW"));
        assertSupplierAnalyses(sharedAnalysesB, userB, userA, Set.of("B-SHARED-REVIEW"));

        assertForeignAndUnknownSupplierKeysAreIndistinguishable(
                baseUri, sessionA, userA, userB, B_ONLY_SUPPLIER);
        assertForeignAndUnknownSupplierKeysAreIndistinguishable(
                baseUri, sessionB, userB, userA, A_ONLY_SUPPLIER);
    }

    @Test
    void fullDashboardAndSupplierSnapshotsAreOwnerScopedAcrossProvidersAndCurrencies() throws Exception {
        UserFixture userA = insertActiveVerifiedUser("insights-snapshot-a");
        UserFixture userB = insertActiveVerifiedUser("insights-snapshot-b");
        GuestFixture guest = insertActiveGuest();

        Instant octoberStart = Instant.parse("2026-10-01T00:00:00Z");
        Instant novemberStart = Instant.parse("2026-11-01T00:00:00Z");

        AnalysisFixture aSharedStart = insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "PENDING", "MATCHED",
                "Acme Supplies", SHARED_SUPPLIER, "A-SHARED-START", "USD",
                "100.0000", "100.0000", "0.0000",
                octoberStart.minusSeconds(60), octoberStart);
        insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "PENDING", "MATCHED",
                "Acme Supplies", SHARED_SUPPLIER, "A-SHARED-MATCHED", "USD",
                "110.0000", "110.0000", "0.0000",
                octoberStart.plusSeconds(86_340), octoberStart.plusSeconds(86_400));
        AnalysisFixture aSharedPending = insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Acme Supplies", SHARED_SUPPLIER, "A-SHARED-PENDING", "USD",
                "100.0000", "120.0000", "20.0000",
                octoberStart.plusSeconds(172_740), octoberStart.plusSeconds(172_800));
        insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "REVIEWED", "REVIEW_REQUIRED",
                "Acme Supplies", SHARED_SUPPLIER, "A-SHARED-REVIEWED", "USD",
                "100.0000", "110.0000", "10.0000",
                octoberStart.plusSeconds(259_140), octoberStart.plusSeconds(259_200));

        insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "PENDING", "MATCHED",
                "Northwind Parts", "usd-alt-supplier", "A-USD-ALT-1", "USD",
                "200.0000", "200.0000", "0.0000",
                octoberStart.plusSeconds(345_540), octoberStart.plusSeconds(345_600));
        insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "PENDING", "MATCHED",
                "Northwind Parts", "usd-alt-supplier", "A-USD-ALT-2", "USD",
                "210.0000", "210.0000", "0.0000",
                octoberStart.plusSeconds(431_940), octoberStart.plusSeconds(432_000));
        AnalysisFixture aEuroPending = insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Euro Components", "eur-supplier", "A-EUR-PENDING", "EUR",
                "270.0000", "300.0000", "30.0000",
                octoberStart.plusSeconds(518_340), octoberStart.plusSeconds(518_400));
        insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "PENDING", "MATCHED",
                "Euro Components", "eur-supplier", "A-EUR-MATCHED", "EUR",
                "320.0000", "320.0000", "0.0000",
                octoberStart.plusSeconds(604_740), octoberStart.plusSeconds(604_800));

        insertDiscrepancy(aSharedPending.id(), "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(aSharedPending.id(), "TAX_MISMATCH");
        insertDiscrepancy(aSharedStart.id(), "UNIT_PRICE_MISMATCH");
        insertDiscrepancy(aEuroPending.id(), "TOTAL_MISMATCH");

        insertScenarioAnalysis(
                userA.id(), null, "FAILED", "PENDING", null,
                null, null, "A-FAILED-1", null, null, null, null,
                octoberStart.plusSeconds(9 * 86_400), null);
        insertScenarioAnalysis(
                userA.id(), null, "FAILED", "PENDING", null,
                null, null, "A-FAILED-2", null, null, null, null,
                octoberStart.plusSeconds(10 * 86_400), null);
        insertScenarioAnalysis(
                userA.id(), null, "EXTRACTING", "PENDING", null,
                null, null, "A-EXTRACTING", null, null, null, null,
                octoberStart.plusSeconds(11 * 86_400), null);
        insertScenarioAnalysis(
                userA.id(), null, "UPLOADING", "PENDING", null,
                null, null, "A-UPLOADING", null, null, null, null,
                octoberStart.plusSeconds(12 * 86_400), null);

        // The beginning of the window is inclusive; the next month's boundary is excluded.
        insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "PENDING", "MATCHED",
                "Outside Period", null, "A-BEFORE-WINDOW", "USD",
                "777777.0000", "777777.0000", "0.0000",
                octoberStart.minusSeconds(120), octoberStart.minusSeconds(1));
        insertScenarioAnalysis(
                userA.id(), null, "COMPLETED", "PENDING", "MATCHED",
                "Outside Period", null, "A-NEXT-MONTH", "USD",
                "999999.0000", "999999.0000", "0.0000",
                novemberStart.minusSeconds(60), novemberStart);

        insertScenarioAnalysis(
                userB.id(), null, "COMPLETED", "PENDING", "MATCHED",
                "Acme Supplies", SHARED_SUPPLIER, "B-SHARED-MATCHED", "USD",
                "9000.0000", "9000.0000", "0.0000",
                octoberStart.plusSeconds(13 * 86_400), octoberStart.plusSeconds(13 * 86_400 + 60));
        AnalysisFixture guestAnalysis = insertScenarioAnalysis(
                null, guest.id(), "COMPLETED", "PENDING", "REVIEW_REQUIRED",
                "Acme Supplies", SHARED_SUPPLIER, "G-SHARED-REVIEW", "USD",
                "5000.0000", "4600.0000", "-400.0000",
                octoberStart.plusSeconds(14 * 86_400), octoberStart.plusSeconds(14 * 86_400 + 60));
        insertDiscrepancy(guestAnalysis.id(), "TOTAL_MISMATCH");

        URI baseUri = applicationUri();
        AuthenticatedSession sessionA = login(baseUri, userA);
        AuthenticatedSession sessionB = login(baseUri, userB);
        String octoberDashboardPath = "/api/dashboard/summary?from=2026-10-01&to=2026-10-31";

        HttpResponse<String> dashboardA = get(baseUri, octoberDashboardPath, sessionA, guest.id());
        assertOkNoStore(dashboardA);
        assertNoAccountIdentity(dashboardA.body(), userA, userB);
        JsonNode dashboardABody = objectMapper.readTree(dashboardA.body());
        assertEquals(8, dashboardABody.path("completedAnalyses").asLong());
        assertEquals(5, dashboardABody.path("matchedAnalyses").asLong());
        assertEquals(3, dashboardABody.path("analysesWithDifferences").asLong());
        assertEquals(0, new BigDecimal("62.50").compareTo(
                new BigDecimal(dashboardABody.path("matchedPercent").toString())));
        assertEquals(2, dashboardABody.path("needsAttentionCount").asLong());
        assertEquals(2, dashboardABody.path("failedCount").asLong());
        assertCurrencyBreakdown(dashboardABody.path("totalInvoicedByCurrency"),
                Map.of("USD", new BigDecimal("850.0000"), "EUR", new BigDecimal("620.0000")),
                Map.of("USD", 6L, "EUR", 2L));
        assertCurrencyBreakdown(dashboardABody.path("potentialDifferencesByCurrency"),
                Map.of("USD", new BigDecimal("30.0000"), "EUR", new BigDecimal("30.0000")),
                Map.of("USD", 2L, "EUR", 1L));

        Map<String, Long> topSupplierCounts = new HashMap<>();
        Map<String, String> topSupplierNames = new HashMap<>();
        dashboardABody.path("topSuppliers").forEach(item -> {
            topSupplierCounts.put(item.path("supplierKey").asString(), item.path("completedAnalyses").asLong());
            topSupplierNames.put(item.path("supplierKey").asString(), item.path("supplierName").asString());
        });
        assertEquals(Map.of(SHARED_SUPPLIER, 4L, "usd-alt-supplier", 2L, "eur-supplier", 2L),
                topSupplierCounts);
        assertEquals(Map.of(SHARED_SUPPLIER, "Acme Supplies", "usd-alt-supplier", "Northwind Parts",
                "eur-supplier", "Euro Components"), topSupplierNames);
        assertDiscrepancyFrequencies(dashboardABody.path("commonDiscrepancyTypes"), Map.of(
                "UNIT_PRICE_MISMATCH", 2L, "TAX_MISMATCH", 1L, "TOTAL_MISMATCH", 1L));

        HttpResponse<String> recentA = get(baseUri,
                "/api/dashboard/recent?view=RECENT&page=0&size=20", sessionA, guest.id());
        assertOkNoStore(recentA);
        assertNoAccountIdentity(recentA.body(), userA, userB);
        assertEquals(14, objectMapper.readTree(recentA.body()).path("totalElements").asLong());
        HttpResponse<String> needsAttentionA = get(baseUri,
                "/api/dashboard/recent?view=NEEDS_ATTENTION&page=0&size=20", sessionA, guest.id());
        assertRecentInvoices(needsAttentionA, userA, userB,
                Set.of("A-SHARED-PENDING", "A-EUR-PENDING"));

        HttpResponse<String> suppliersA = get(baseUri, "/api/suppliers?page=0&size=20", sessionA, guest.id());
        assertSupplierKeys(suppliersA, userA, userB, Map.of(
                SHARED_SUPPLIER, "Acme Supplies",
                "usd-alt-supplier", "Northwind Parts",
                "eur-supplier", "Euro Components"));

        HttpResponse<String> sharedSummaryA = get(baseUri,
                "/api/suppliers/" + SHARED_SUPPLIER + "/summary", sessionA, guest.id());
        assertSupplierSummary(sharedSummaryA, userA, userB, "Acme Supplies", 4, 2,
                "440.0000", 2, Set.of("A-SHARED-START", "A-SHARED-MATCHED",
                        "A-SHARED-PENDING", "A-SHARED-REVIEWED"));
        JsonNode sharedSummaryABody = objectMapper.readTree(sharedSummaryA.body());
        assertEquals(0, new BigDecimal("50.00").compareTo(
                new BigDecimal(sharedSummaryABody.path("differenceRatePercent").toString())));
        assertCurrencyBreakdown(sharedSummaryABody.path("potentialDifferencesByCurrency"),
                Map.of("USD", new BigDecimal("30.0000")), Map.of("USD", 2L));
        assertDiscrepancyFrequencies(sharedSummaryABody.path("commonDiscrepancyTypes"),
                Map.of("UNIT_PRICE_MISMATCH", 2L, "TAX_MISMATCH", 1L));

        HttpResponse<String> sharedAnalysesA = get(baseUri,
                "/api/suppliers/" + SHARED_SUPPLIER + "/analyses?page=0&size=20", sessionA, guest.id());
        assertSupplierAnalyses(sharedAnalysesA, userA, userB, Set.of(
                "A-SHARED-START", "A-SHARED-MATCHED", "A-SHARED-PENDING", "A-SHARED-REVIEWED"));

        HttpResponse<String> dashboardB = get(baseUri, octoberDashboardPath, sessionB, guest.id());
        assertOkNoStore(dashboardB);
        assertNoAccountIdentity(dashboardB.body(), userB, userA);
        JsonNode dashboardBBody = objectMapper.readTree(dashboardB.body());
        assertEquals(1, dashboardBBody.path("completedAnalyses").asLong());
        assertEquals(1, dashboardBBody.path("matchedAnalyses").asLong());
        assertEquals(0, dashboardBBody.path("analysesWithDifferences").asLong());
        assertEquals(0, new BigDecimal("100.00").compareTo(
                new BigDecimal(dashboardBBody.path("matchedPercent").toString())));
        assertCurrencyBreakdown(dashboardBBody.path("totalInvoicedByCurrency"),
                Map.of("USD", new BigDecimal("9000.0000")), Map.of("USD", 1L));
        assertTrue(dashboardBBody.path("commonDiscrepancyTypes").isEmpty());

        HttpResponse<String> suppliersB = get(baseUri, "/api/suppliers?page=0&size=20", sessionB, guest.id());
        assertSupplierKeys(suppliersB, userB, userA, Map.of(SHARED_SUPPLIER, "Acme Supplies"));
        HttpResponse<String> sharedSummaryB = get(baseUri,
                "/api/suppliers/" + SHARED_SUPPLIER + "/summary", sessionB, guest.id());
        assertSupplierSummary(sharedSummaryB, userB, userA, "Acme Supplies", 1, 1,
                "9000.0000", 0, Set.of("B-SHARED-MATCHED"));
        HttpResponse<String> sharedAnalysesB = get(baseUri,
                "/api/suppliers/" + SHARED_SUPPLIER + "/analyses?page=0&size=20", sessionB, guest.id());
        assertSupplierAnalyses(sharedAnalysesB, userB, userA, Set.of("B-SHARED-MATCHED"));
    }

    @Test
    void guestAndAnonymousReadsAreRejectedWithoutCreatingOrRefreshingGuestState() throws Exception {
        GuestFixture guest = insertActiveGuest();
        Instant completedBase = currentMonthFixtureTime();
        insertAnalysis(null, guest.id(), "REVIEW_REQUIRED", "Guest Exclusive Vendor", GUEST_ONLY_SUPPLIER,
                "G-REF-1", "GUEST-ONLY-INVOICE", "USD", "9999.0000", "9999.0000", "0.0000",
                completedBase.minusSeconds(1));
        long guestCountBefore = guestSessionCount();
        Instant lastSeenBefore = guestLastSeen(guest.id());

        URI baseUri = applicationUri();
        for (String path : List.of(
                dashboardSummaryPath(),
                "/api/dashboard/recent?view=RECENT&page=0&size=10",
                "/api/dashboard/recent?view=NEEDS_ATTENTION&page=0&size=10",
                "/api/suppliers?page=0&size=20",
                "/api/suppliers/" + GUEST_ONLY_SUPPLIER + "/summary",
                "/api/suppliers/" + GUEST_ONLY_SUPPLIER + "/analyses")) {
            HttpResponse<String> response = get(baseUri, path, null, guest.id());
            assertEquals(401, response.statusCode(), response.body());
            assertEquals("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication is required.\"}",
                    response.body());
            assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
            assertNoPrivateInsightsFields(response.body());
        }

        assertEquals(guestCountBefore, guestSessionCount());
        assertEquals(lastSeenBefore, guestLastSeen(guest.id()));
    }

    @Test
    void repeatedInsightsReadsAfterNoteAndReviewChangesNeverRegenerateOrWriteAnalysisState() throws Exception {
        UserFixture user = insertActiveVerifiedUser("insights-no-regeneration");
        Instant completedAt = currentMonthFixtureTime();
        assertTrue(completedAt.isBefore(clock.instant()), "Completed fixtures must precede review mutations.");
        AnalysisFixture analysis = insertAnalysis(
                user.id(), null, "REVIEW_REQUIRED", "No Regeneration Vendor", "no-regeneration-vendor",
                "NR-REF-1", "NR-INV-1", "USD", "100.0000", "125.0000", "25.0000", completedAt);
        insertCompletedJob(analysis.id(), completedAt);
        insertResolvedDiscrepancy(analysis.id(), completedAt);

        URI baseUri = applicationUri();
        AuthenticatedSession session = login(baseUri, user);
        HttpResponse<String> initialSummary = get(baseUri, dashboardSummaryPath(), session, null);
        assertOkNoStore(initialSummary);
        assertEquals(1, objectMapper.readTree(initialSummary.body()).path("needsAttentionCount").asLong());

        String csrfToken = csrfToken(baseUri, session);
        HttpResponse<String> noteResponse = sendJson(
                baseUri,
                "/api/analyses/" + analysis.id() + "/note",
                "PUT",
                "{\"text\":\"INSIGHTS_NO_REPROCESSING_PRIVATE_NOTE\",\"expectedVersion\":null}",
                session,
                csrfToken);
        assertEquals(200, noteResponse.statusCode(), noteResponse.body());

        HttpResponse<String> reviewResponse = sendJson(
                baseUri,
                "/api/analyses/" + analysis.id() + "/review-status",
                "PATCH",
                "{\"reviewStatus\":\"REVIEWED\"}",
                session,
                csrfToken);
        assertEquals(200, reviewResponse.statusCode(), reviewResponse.body());
        JsonNode reviewBody = objectMapper.readTree(reviewResponse.body());
        assertEquals("REVIEWED", reviewBody.path("reviewStatus").asString());
        assertEquals(clock.instant(), Instant.parse(reviewBody.path("reviewedAt").asString()));

        List<Map<String, Object>> analysisStateBefore = analysisState(analysis.id());
        assertEquals("REVIEWED", analysisStateBefore.getFirst().get("review_status"));
        assertEquals(timestamp(clock.instant()), analysisStateBefore.getFirst().get("reviewed_at"));
        List<Map<String, Object>> jobStateBefore = jobState(analysis.id());
        List<Map<String, Object>> discrepancyStateBefore = discrepancyState(analysis.id());
        clearInsightReadCollaboratorInvocations();

        List<String> expectedBodies = null;
        for (int repeat = 0; repeat < 3; repeat++) {
            HttpResponse<String> summary = get(baseUri, dashboardSummaryPath(), session, null);
            HttpResponse<String> recent = get(
                    baseUri, "/api/dashboard/recent?view=RECENT&page=0&size=10", session, null);
            HttpResponse<String> needsAttention = get(
                    baseUri, "/api/dashboard/recent?view=NEEDS_ATTENTION&page=0&size=10", session, null);
            HttpResponse<String> suppliers = get(baseUri, "/api/suppliers?page=0&size=20", session, null);
            HttpResponse<String> supplierSummary = get(
                    baseUri, "/api/suppliers/no-regeneration-vendor/summary", session, null);
            HttpResponse<String> supplierAnalyses = get(
                    baseUri, "/api/suppliers/no-regeneration-vendor/analyses", session, null);

            for (HttpResponse<String> response : List.of(
                    summary, recent, needsAttention, suppliers, supplierSummary, supplierAnalyses)) {
                assertOkNoStore(response);
                assertFalse(response.body().contains("INSIGHTS_NO_REPROCESSING_PRIVATE_NOTE"));
            }

            assertEquals(0, objectMapper.readTree(summary.body()).path("needsAttentionCount").asLong());
            assertEquals(1, objectMapper.readTree(summary.body()).path("completedAnalyses").asLong());
            assertEquals(0, objectMapper.readTree(needsAttention.body()).path("totalElements").asLong());
            assertEquals(1, objectMapper.readTree(recent.body()).path("totalElements").asLong());
            assertEquals(1, objectMapper.readTree(suppliers.body()).path("totalElements").asLong());
            assertEquals(1, objectMapper.readTree(supplierAnalyses.body()).path("totalElements").asLong());

            List<String> currentBodies = List.of(
                    summary.body(), recent.body(), needsAttention.body(), suppliers.body(),
                    supplierSummary.body(), supplierAnalyses.body());
            if (expectedBodies == null) {
                expectedBodies = currentBodies;
            } else {
                assertEquals(expectedBodies, currentBodies, "Repeated Insights reads should be deterministic.");
            }
        }

        assertEquals(analysisStateBefore, analysisState(analysis.id()));
        assertEquals(jobStateBefore, jobState(analysis.id()));
        assertEquals(discrepancyStateBefore, discrepancyState(analysis.id()));
        verifyNoInsightReadSideEffects();
    }

    private void assertSummaryOwner(
            HttpResponse<String> response,
            UserFixture owner,
            UserFixture other,
            Map<String, String> expectedTopSuppliers,
            long completed,
            long matched,
            String firstCurrency,
            String firstAmount,
            String secondCurrency,
            String secondAmount) throws Exception {
        assertOkNoStore(response);
        assertNoAccountIdentity(response.body(), owner, other);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(completed, body.path("completedAnalyses").asLong());
        assertEquals(matched, body.path("matchedAnalyses").asLong());
        assertEquals(completed - matched, body.path("analysesWithDifferences").asLong());
        assertAmount(firstAmount, currencyAmount(body, firstCurrency));
        assertAmount(secondAmount, currencyAmount(body, secondCurrency));
        Map<String, String> topSuppliers = new HashMap<>();
        body.path("topSuppliers").forEach(item -> topSuppliers.put(
                item.path("supplierKey").asString(), item.path("supplierName").asString()));
        assertEquals(expectedTopSuppliers, topSuppliers);
    }

    private void assertRecentInvoices(
            HttpResponse<String> response,
            UserFixture owner,
            UserFixture other,
            Set<String> expectedInvoices) throws Exception {
        assertOkNoStore(response);
        assertNoAccountIdentity(response.body(), owner, other);
        JsonNode body = objectMapper.readTree(response.body());
        Set<String> invoices = new HashSet<>();
        body.path("content").forEach(item -> invoices.add(item.path("invoiceNumber").asString()));
        assertEquals(expectedInvoices, invoices);
        assertEquals(expectedInvoices.size(), body.path("totalElements").asLong());
    }

    private void assertSupplierKeys(
            HttpResponse<String> response,
            UserFixture owner,
            UserFixture other,
            Map<String, String> expectedSuppliers) throws Exception {
        assertOkNoStore(response);
        assertNoAccountIdentity(response.body(), owner, other);
        JsonNode body = objectMapper.readTree(response.body());
        Map<String, String> suppliers = new HashMap<>();
        body.path("content").forEach(item -> suppliers.put(
                item.path("supplierKey").asString(), item.path("supplierName").asString()));
        assertEquals(expectedSuppliers, suppliers);
        assertEquals(expectedSuppliers.size(), body.path("totalElements").asLong());
    }

    private void assertSupplierSummary(
            HttpResponse<String> response,
            UserFixture owner,
            UserFixture other,
            String supplierName,
            long completed,
            long matched,
            String invoicedUsd,
            long unitPriceDiscrepancies,
            Set<String> expectedRecentInvoices) throws Exception {
        assertOkNoStore(response);
        assertNoAccountIdentity(response.body(), owner, other);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(SHARED_SUPPLIER, body.path("supplierKey").asString());
        assertEquals(supplierName, body.path("supplierName").asString());
        assertEquals(completed, body.path("completedAnalyses").asLong());
        assertEquals(matched, body.path("matchedAnalyses").asLong());
        assertEquals(completed - matched, body.path("analysesWithDifferences").asLong());
        assertAmount(invoicedUsd, currencyAmount(body, "totalInvoicedByCurrency", "USD"));
        assertEquals(unitPriceDiscrepancies,
                discrepancyCount(body.path("commonDiscrepancyTypes"), "UNIT_PRICE_MISMATCH"));
        Set<String> recentInvoices = new HashSet<>();
        body.path("recentAnalyses").forEach(item -> recentInvoices.add(item.path("invoiceNumber").asString()));
        assertEquals(expectedRecentInvoices, recentInvoices);
    }

    private void assertSupplierAnalyses(
            HttpResponse<String> response,
            UserFixture owner,
            UserFixture other,
            Set<String> expectedInvoices) throws Exception {
        assertOkNoStore(response);
        assertNoAccountIdentity(response.body(), owner, other);
        JsonNode body = objectMapper.readTree(response.body());
        Set<String> invoices = new HashSet<>();
        body.path("content").forEach(item -> invoices.add(item.path("invoiceNumber").asString()));
        assertEquals(expectedInvoices, invoices);
        assertEquals(expectedInvoices.size(), body.path("totalElements").asLong());
        assertEquals(SHARED_SUPPLIER, body.path("supplierKey").asString());
    }

    private void assertForeignAndUnknownSupplierKeysAreIndistinguishable(
            URI baseUri,
            AuthenticatedSession session,
            UserFixture owner,
            UserFixture other,
            String foreignSupplierKey) throws Exception {
        HttpResponse<String> foreignSummary = get(baseUri,
                "/api/suppliers/" + foreignSupplierKey + "/summary", session, null);
        HttpResponse<String> unknownSummary = get(baseUri,
                "/api/suppliers/unknown-supplier-key/summary", session, null);
        assertNotFound(foreignSummary);
        assertNotFound(unknownSummary);
        assertEquals(SUPPLIER_NOT_FOUND, foreignSummary.body());
        assertEquals(unknownSummary.body(), foreignSummary.body());
        assertNoAccountIdentity(foreignSummary.body(), owner, other);

        HttpResponse<String> foreignAnalyses = get(baseUri,
                "/api/suppliers/" + foreignSupplierKey + "/analyses", session, null);
        HttpResponse<String> unknownAnalyses = get(baseUri,
                "/api/suppliers/unknown-supplier-key/analyses", session, null);
        assertNotFound(foreignAnalyses);
        assertNotFound(unknownAnalyses);
        assertEquals(SUPPLIER_NOT_FOUND, foreignAnalyses.body());
        assertEquals(unknownAnalyses.body(), foreignAnalyses.body());
        assertNoAccountIdentity(foreignAnalyses.body(), owner, other);
    }

    private UserFixture insertActiveVerifiedUser(String prefix) {
        UUID id = UUID.randomUUID();
        String email = prefix + "-" + UUID.randomUUID() + "@example.com";
        jdbcTemplate.update("""
                INSERT INTO invoward.users
                    (id, display_name, email, password_hash, email_verified, status)
                VALUES (?, 'Insights Ownership User', ?, ?, true, 'ACTIVE')
                """, id, email, passwordEncoder.encode(RAW_PASSWORD));
        return new UserFixture(id, email);
    }

    private GuestFixture insertActiveGuest() {
        UUID id = UUID.randomUUID();
        guestSessionIdMarker = id;
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant expiresAt = now.plusSeconds(86_400);
        jdbcTemplate.update("""
                INSERT INTO invoward.guest_sessions (id, expires_at, last_seen_at, created_at)
                VALUES (?, ?, ?, ?)
                """, id, timestamp(expiresAt), timestamp(now), timestamp(now));
        return new GuestFixture(id);
    }

    private AnalysisFixture insertAnalysis(
            UUID userId,
            UUID guestSessionId,
            String reconciliationStatus,
            String supplierName,
            String supplierKey,
            String referenceNumber,
            String invoiceNumber,
            String currency,
            String referenceTotal,
            String invoicedTotal,
            String difference,
            Instant completedAt) {
        Instant expiresAt = guestSessionId == null ? null : timestamplessGuestExpiry(guestSessionId);
        Instant createdAt = completedAt.minusSeconds(60);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO invoward.analyses
                    (id, user_id, guest_session_id, status, review_status, reconciliation_status,
                     supplier_name, supplier_key, reference_type, reference_number, invoice_number,
                     currency, reference_total, invoiced_total, difference, price_tolerance_percent,
                     retryable, version, completed_at, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, 'COMPLETED', 'PENDING', ?, ?, ?, 'QUOTE', ?, ?, ?, ?, ?, ?,
                        0, FALSE, 0, ?, ?, ?, ?)
                """, id, userId, guestSessionId, reconciliationStatus, supplierName, supplierKey,
                referenceNumber, invoiceNumber, currency, new BigDecimal(referenceTotal),
                new BigDecimal(invoicedTotal), new BigDecimal(difference), timestamp(completedAt),
                expiresAt == null ? null : timestamp(expiresAt), timestamp(createdAt), timestamp(completedAt));
        return new AnalysisFixture(id, invoiceNumber);
    }

    private AnalysisFixture insertScenarioAnalysis(
            UUID userId,
            UUID guestSessionId,
            String status,
            String reviewStatus,
            String reconciliationStatus,
            String supplierName,
            String supplierKey,
            String invoiceNumber,
            String currency,
            String referenceTotal,
            String invoicedTotal,
            String difference,
            Instant createdAt,
            Instant completedAt) {
        UUID id = UUID.randomUUID();
        Instant reviewedAt = "REVIEWED".equals(reviewStatus) ? completedAt : null;
        Instant updatedAt = completedAt == null ? createdAt : completedAt;
        Instant expiresAt = guestSessionId == null ? null : timestamplessGuestExpiry(guestSessionId);

        jdbcTemplate.update("""
                INSERT INTO invoward.analyses
                    (id, user_id, guest_session_id, status, review_status, reviewed_at, reconciliation_status,
                     supplier_name, supplier_key, reference_type, reference_number, invoice_number,
                     currency, reference_total, invoiced_total, difference, price_tolerance_percent,
                     retryable, version, completed_at, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,
                        CASE WHEN ? = 'COMPLETED' THEN 'QUOTE' ELSE NULL END,
                        NULL, ?, ?, ?, ?, ?, 0, FALSE, 0, ?, ?, ?, ?)
                """,
                id,
                userId,
                guestSessionId,
                status,
                reviewStatus,
                reviewedAt == null ? null : timestamp(reviewedAt),
                reconciliationStatus,
                supplierName,
                supplierKey,
                status,
                invoiceNumber,
                currency,
                decimalOrNull(referenceTotal),
                decimalOrNull(invoicedTotal),
                decimalOrNull(difference),
                completedAt == null ? null : timestamp(completedAt),
                expiresAt == null ? null : timestamp(expiresAt),
                timestamp(createdAt),
                timestamp(updatedAt));
        return new AnalysisFixture(id, invoiceNumber);
    }

    private void insertCompletedJob(UUID analysisId, Instant completedAt) {
        jdbcTemplate.update("""
                INSERT INTO invoward.analysis_jobs
                    (id, analysis_id, status, current_stage, attempt_count, retryable,
                     last_error_code, last_error_message, started_at, completed_at, created_at, updated_at)
                VALUES (?, ?, 'COMPLETED', 'COMPLETED', 2, FALSE, NULL, NULL, ?, ?, ?, ?)
                """, UUID.randomUUID(), analysisId,
                timestamp(completedAt.minusSeconds(3)), timestamp(completedAt),
                timestamp(completedAt.minusSeconds(60)), timestamp(completedAt));
    }

    private void insertResolvedDiscrepancy(UUID analysisId, Instant resolvedAt) {
        UUID discrepancyId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO invoward.discrepancies
                    (id, analysis_id, type, explanation, resolution_status, resolution_reason,
                     resolution_note, version, resolved_at, created_at, updated_at)
                VALUES (?, ?, 'UNIT_PRICE_MISMATCH', 'Synthetic resolved discrepancy', 'RESOLVED',
                        'APPROVED_CHANGE', 'Synthetic private resolution note', 1, ?, ?, ?)
                """, discrepancyId, analysisId, timestamp(resolvedAt),
                timestamp(resolvedAt.minusSeconds(5)), timestamp(resolvedAt));
    }

    private List<Map<String, Object>> analysisState(UUID analysisId) {
        return jdbcTemplate.queryForList("""
                SELECT status, review_status, reviewed_at, reconciliation_status, version, completed_at, updated_at
                FROM invoward.analyses
                WHERE id = ?
                """, analysisId);
    }

    private List<Map<String, Object>> jobState(UUID analysisId) {
        return jdbcTemplate.queryForList("""
                SELECT status, current_stage, attempt_count, retryable, last_error_code, last_error_message,
                       started_at, completed_at, created_at, updated_at
                FROM invoward.analysis_jobs
                WHERE analysis_id = ?
                """, analysisId);
    }

    private List<Map<String, Object>> discrepancyState(UUID analysisId) {
        return jdbcTemplate.queryForList("""
                SELECT id, resolution_status, resolution_reason, resolution_note, resolved_at, version, updated_at
                FROM invoward.discrepancies
                WHERE analysis_id = ?
                ORDER BY id
                """, analysisId);
    }

    private void clearInsightReadCollaboratorInvocations() {
        clearInvocations(
                analysisRepository,
                analysisJobRepository,
                processingSignalPublisher,
                processingWorker,
                documentIntelligence,
                ambiguousLineMatcher,
                runLineItemMatchingService,
                reconcileAnalysisService,
                generateReportService,
                reportGenerator,
                reportStorage,
                documentStorage);
    }

    private void verifyNoInsightReadSideEffects() {
        verifyNoInteractions(
                analysisRepository,
                analysisJobRepository,
                processingSignalPublisher,
                processingWorker,
                documentIntelligence,
                ambiguousLineMatcher,
                runLineItemMatchingService,
                reconcileAnalysisService,
                generateReportService,
                reportGenerator,
                reportStorage,
                documentStorage);
    }

    private Instant timestamplessGuestExpiry(UUID guestSessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM invoward.guest_sessions WHERE id = ?",
                (result, row) -> result.getTimestamp("expires_at").toInstant(), guestSessionId);
    }

    private long guestSessionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoward.guest_sessions", Long.class);
    }

    private Instant guestLastSeen(UUID guestSessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_seen_at FROM invoward.guest_sessions WHERE id = ?",
                (result, row) -> result.getTimestamp("last_seen_at").toInstant(), guestSessionId);
    }

    private void insertDiscrepancy(UUID analysisId, String type) {
        insertDiscrepancy(analysisId, type, "Synthetic ownership fixture discrepancy");
    }

    private void insertDiscrepancy(UUID analysisId, String type, String explanation) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbcTemplate.update("""
                INSERT INTO invoward.discrepancies
                    (id, analysis_id, type, explanation, resolution_status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'OPEN', 0, ?, ?)
                """, UUID.randomUUID(), analysisId, type, explanation, timestamp(now), timestamp(now));
    }

    private void insertPrivateAnalysisData(AnalysisFixture analysis, Instant generatedAt) {
        Instant createdAt = generatedAt.minusSeconds(2);
        jdbcTemplate.update("""
                INSERT INTO invoward.analysis_notes (id, analysis_id, note_text, version, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?)
                """, UUID.randomUUID(), analysis.id(), PRIVATE_NOTE_SENTINEL,
                timestamp(createdAt), timestamp(createdAt));

        jdbcTemplate.update("""
                INSERT INTO invoward.generated_reports
                    (id, analysis_id, storage_key, content_type, size_bytes, sha256, expires_at, generated_at)
                VALUES (?, ?, ?, 'application/pdf', 128, ?, NULL, ?)
                """, UUID.randomUUID(), analysis.id(), PRIVATE_STORAGE_KEY_SENTINEL,
                "a".repeat(64), timestamp(generatedAt));

        jdbcTemplate.update("""
                INSERT INTO invoward.share_links (id, analysis_id, token_hash, expires_at, revoked_at, created_at)
                VALUES (?, ?, ?, ?, NULL, ?)
                """, UUID.randomUUID(), analysis.id(), sha256(PRIVATE_SHARE_TOKEN_SENTINEL),
                timestamp(generatedAt.plusSeconds(86_400)), timestamp(generatedAt));
    }

    private AuthenticatedSession login(URI baseUri, UserFixture user) throws Exception {
        HttpResponse<String> csrfResponse = get(baseUri, "/api/auth/csrf", null, null);
        assertEquals(200, csrfResponse.statusCode());
        JsonNode csrf = objectMapper.readTree(csrfResponse.body());
        assertEquals("X-CSRF-TOKEN", csrf.path("headerName").asString());
        String preAuthenticationCookie = cookieValue(csrfResponse, SESSION_COOKIE);

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/auth/login"))
                        .header("Cookie", SESSION_COOKIE + "=" + preAuthenticationCookie)
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf.path("token").asString())
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                                Map.of("email", user.email(), "password", RAW_PASSWORD))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String authenticatedCookie = cookieValue(response, SESSION_COOKIE);
        assertNotEquals(preAuthenticationCookie, authenticatedCookie,
                "Login must rotate the pre-authentication session ID.");
        return new AuthenticatedSession(authenticatedCookie);
    }

    private String csrfToken(URI baseUri, AuthenticatedSession session) throws Exception {
        HttpResponse<String> response = get(baseUri, "/api/auth/csrf", session, null);
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body()).path("token").asString();
    }

    private HttpResponse<String> sendJson(
            URI baseUri,
            String path,
            String method,
            String body,
            AuthenticatedSession session,
            String csrfToken) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(baseUri.resolve(path))
                        .header("Cookie", SESSION_COOKIE + "=" + session.cookie())
                        .header("X-CSRF-TOKEN", csrfToken)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(
            URI baseUri, String path, AuthenticatedSession session, UUID guestSessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path));
        StringBuilder cookies = new StringBuilder();
        if (session != null) {
            cookies.append(SESSION_COOKIE).append('=').append(session.cookie());
        }
        if (guestSessionId != null) {
            if (!cookies.isEmpty()) {
                cookies.append("; ");
            }
            cookies.append(GUEST_COOKIE).append('=').append(guestSessionId);
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookies.toString());
        }
        return httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private String dashboardSummaryPath() {
        YearMonth currentMonth = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        LocalDate from = currentMonth.atDay(1);
        LocalDate to = currentMonth.atEndOfMonth();
        return "/api/dashboard/summary?from=" + from + "&to=" + to;
    }

    private void assertOkNoStore(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
    }

    private void assertNotFound(HttpResponse<String> response) {
        assertEquals(404, response.statusCode());
        assertEquals("application/json", mediaType(response));
    }

    private void assertNoAccountIdentity(String body, UserFixture owner, UserFixture other) {
        assertFalse(body.contains(owner.id().toString()));
        assertFalse(body.contains(other.id().toString()));
        assertFalse(body.contains(owner.email()));
        assertFalse(body.contains(other.email()));
        assertFalse(body.contains(guestSessionIdMarker.toString()));
        assertNoPrivateInsightsFields(body);
    }

    private static void assertNoPrivateInsightsFields(String body) {
        for (String field : List.of(
                "userId", "guestSessionId", "sessionId", "email", "privateNote", "noteText",
                "shareToken", "tokenHash", "storageKey", "sha256", "reportUrl", "signedUrl",
                "presignedUrl", "sourceText", "boundingBox", "aiPayload")) {
            assertFalse(body.contains("\"" + field + "\""), "Unexpected private response field: " + field);
        }
        for (String privateValue : List.of(
                PRIVATE_NOTE_SENTINEL,
                PRIVATE_SHARE_TOKEN_SENTINEL,
                PRIVATE_STORAGE_KEY_SENTINEL,
                PRIVATE_REPORT_URL_SENTINEL,
                PRIVATE_AI_TEXT_SENTINEL)) {
            assertFalse(body.contains(privateValue), "Private fixture value leaked in Insights response.");
        }
    }

    private BigDecimal currencyAmount(JsonNode body, String currency) {
        return currencyAmount(body, "totalInvoicedByCurrency", currency);
    }

    private BigDecimal currencyAmount(JsonNode body, String field, String currency) {
        for (JsonNode item : body.path(field)) {
            if (currency.equals(item.path("currency").asString())) {
                return new BigDecimal(item.path("amount").asString());
            }
        }
        throw new AssertionError("Missing currency " + currency + " in " + field + ".");
    }

    private static void assertCurrencyBreakdown(
            JsonNode rows,
            Map<String, BigDecimal> expectedAmounts,
            Map<String, Long> expectedContributingAnalyses) {
        Map<String, BigDecimal> actualAmounts = new HashMap<>();
        Map<String, Long> actualContributingAnalyses = new HashMap<>();
        rows.forEach(item -> {
            String currency = item.path("currency").asString();
            actualAmounts.put(currency, new BigDecimal(item.path("amount").asString()));
            actualContributingAnalyses.put(currency, item.path("contributingAnalyses").asLong());
        });

        assertEquals(expectedAmounts.keySet(), actualAmounts.keySet());
        expectedAmounts.forEach((currency, expectedAmount) ->
                assertAmount(expectedAmount.toPlainString(), actualAmounts.get(currency)));
        assertEquals(expectedContributingAnalyses, actualContributingAnalyses);
    }

    private static void assertDiscrepancyFrequencies(JsonNode rows, Map<String, Long> expected) {
        Map<String, Long> actual = new HashMap<>();
        rows.forEach(item -> actual.put(item.path("type").asString(), item.path("count").asLong()));
        assertEquals(expected, actual);
    }

    private static BigDecimal decimalOrNull(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private long discrepancyCount(HttpResponse<String> response, String type) throws Exception {
        return discrepancyCount(objectMapper.readTree(response.body()).path("commonDiscrepancyTypes"), type);
    }

    private static long discrepancyCount(JsonNode discrepancies, String type) {
        for (JsonNode item : discrepancies) {
            if (type.equals(item.path("type").asString())) {
                return item.path("count").asLong();
            }
        }
        return 0;
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

    private static String mediaType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElseThrow().split(";", 2)[0];
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private Instant currentMonthFixtureTime() {
        return clock.instant().minusSeconds(60);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {

        @Bean
        @Primary
        Clock insightsTestClock() {
            // Keep the first half of the month covered without depending on the machine's date.
            return Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    private record UserFixture(UUID id, String email) {
    }

    private record GuestFixture(UUID id) {
    }

    private record AnalysisFixture(UUID id, String invoiceNumber) {
    }

    private record AuthenticatedSession(String cookie) {
    }
}
