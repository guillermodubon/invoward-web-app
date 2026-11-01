package io.github.guillermodubon.invoward.insights.api;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.DashboardAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardDataQualityCounts;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentPage;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentQuery;
import io.github.guillermodubon.invoward.insights.application.model.DashboardSummary;
import io.github.guillermodubon.invoward.insights.application.model.DashboardTopSupplier;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPeriod;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.service.GetDashboardSummaryService;
import io.github.guillermodubon.invoward.insights.application.service.InsightsPeriodFactory;
import io.github.guillermodubon.invoward.insights.application.service.ListDashboardRecentService;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class InsightsDashboardControllerTest {

    private static final UUID USER_ID = UUID.fromString("ad98944a-d589-4b10-9eb0-3f3203ce1f0b");
    private static final UUID ANALYSIS_ID = UUID.fromString("b9d32f11-4d55-4a34-8cb2-20e47486dc9f");
    private static final Instant CREATED_AT = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-20T10:05:00Z");

    private final GetDashboardSummaryService summaryService = mock(GetDashboardSummaryService.class);
    private final ListDashboardRecentService recentService = mock(ListDashboardRecentService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthenticatedIdentity identity = new CurrentAccount(
            USER_ID, "Registered User", "private@example.com", true, UserStatus.ACTIVE);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new InsightsDashboardController(
                        summaryService,
                        recentService,
                        new InsightsPeriodFactory(Clock.fixed(
                                Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC))))
                .setControllerAdvice(new InsightsDashboardExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void summaryMapsTheApprovedContractAndUsesTheAuthenticatedOwnerAndUtcPeriod() throws Exception {
        DashboardSummary summary = dashboardSummary();
        when(summaryService.get(USER_ID, LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-30")))
                .thenReturn(summary);
        authenticate();
        UUID untrustedUserId = UUID.fromString("770e393d-0ef8-46ca-a7ee-48c0ccf4a18c");

        String body = mockMvc.perform(get("/api/dashboard/summary")
                        .param("from", "2026-10-02")
                        .param("to", "2026-10-30")
                        .param("userId", untrustedUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.period.from").value("2026-10-01"))
                .andExpect(jsonPath("$.period.to").value("2026-11-01"))
                .andExpect(jsonPath("$.period.timezone").value("UTC"))
                .andExpect(jsonPath("$.period.basis").value("COMPLETED_AT"))
                .andExpect(jsonPath("$.completedAnalyses").value(2))
                .andExpect(jsonPath("$.matchedAnalyses").value(1))
                .andExpect(jsonPath("$.analysesWithDifferences").value(1))
                .andExpect(jsonPath("$.matchedPercent").value(50.00))
                .andExpect(jsonPath("$.needsAttentionCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(3))
                .andExpect(jsonPath("$.failedCountBasis").value("CREATED_AT"))
                .andExpect(jsonPath("$.totalInvoicedByCurrency[0].currency").value("USD"))
                .andExpect(jsonPath("$.totalInvoicedByCurrency[0].amount").value(125.0000))
                .andExpect(jsonPath("$.potentialDifferencesByCurrency[0].amount").value(5.0000))
                .andExpect(jsonPath("$.potentialDifferencesDisclaimer").value(
                        "Potential differences are absolute invoice-to-reference total variations for analyses "
                                + "requiring review; they are not proven overcharges, recoverable amounts, or savings."))
                .andExpect(jsonPath("$.differenceAmountUnavailableCount").value(1))
                .andExpect(jsonPath("$.missingCurrencyCount").value(1))
                .andExpect(jsonPath("$.unassignedSupplierCount").value(1))
                .andExpect(jsonPath("$.topSuppliers[0].supplierKey").value("acme supplies"))
                .andExpect(jsonPath("$.commonDiscrepancyTypes[0].type").value("TOTAL_MISMATCH"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(Set.of("period", "completedAnalyses", "matchedAnalyses", "analysesWithDifferences",
                        "matchedPercent", "needsAttentionCount", "failedCount", "totalInvoicedByCurrency",
                        "failedCountBasis", "potentialDifferencesByCurrency", "potentialDifferencesDisclaimer",
                        "differenceAmountUnavailableCount", "missingCurrencyCount", "unassignedSupplierCount",
                        "topSuppliers", "commonDiscrepancyTypes"), jsonFieldNames(body));
        assertEquals(Set.of("from", "to", "timezone", "basis"), jsonFieldNames(
                objectMapper.readTree(body).get("period")));
        assertEquals(Set.of("currency", "amount", "contributingAnalyses"), jsonFieldNames(
                objectMapper.readTree(body).get("totalInvoicedByCurrency").get(0)));
        assertFalse(body.contains(USER_ID.toString()));
        assertFalse(body.contains(untrustedUserId.toString()));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("email"));
        assertFalse(body.contains("private@example.com"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains("note"));

        verify(summaryService).get(USER_ID, LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-30"));
        verifyNoInteractions(recentService);
        verifyNoMoreInteractions(summaryService);
    }

    @Test
    void recentMapsAllFiltersAndTheSafePaginatedAnalysisProjection() throws Exception {
        DashboardRecentQuery query = DashboardRecentQuery.of("NEEDS_ATTENTION", 1, 1);
        AnalysisHistoryItem item = historyItem();
        DashboardRecentPage recentPage = new DashboardRecentPage(
                query.view(), new InsightsPage<>(List.of(item), 1, 1, 3));
        when(recentService.list(USER_ID, query)).thenReturn(recentPage);
        authenticate();

        String body = mockMvc.perform(get("/api/dashboard/recent")
                        .param("view", "NEEDS_ATTENTION")
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.view").value("NEEDS_ATTENTION"))
                .andExpect(jsonPath("$.content[0].id").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].reconciliationStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.content[0].invoiceNumber").value("INV-205"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.hasPrevious").value(true))
                .andReturn().getResponse().getContentAsString();

        assertEquals(Set.of("view", "content", "page", "size", "totalElements", "totalPages", "hasNext",
                        "hasPrevious"), jsonFieldNames(body));
        assertEquals(Set.of("id", "status", "reconciliationStatus", "reviewStatus", "reviewedAt",
                        "supplierName", "referenceType", "referenceNumber", "invoiceNumber", "currency",
                        "referenceTotal", "invoicedTotal", "difference", "createdAt", "updatedAt", "completedAt"),
                jsonFieldNames(objectMapper.readTree(body).get("content").get(0)));
        assertFalse(body.contains(USER_ID.toString()));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains("shareToken"));
        assertFalse(body.contains("noteText"));

        verify(recentService).list(USER_ID, query);
        verifyNoInteractions(summaryService);
        verifyNoMoreInteractions(recentService);
    }

    @Test
    void malformedAndOutOfRangeFiltersReturnSafeBadRequestWithoutCallingServices() throws Exception {
        authenticate();
        List<String[]> invalidSummaryParameters = List.of(
                new String[]{"from", "not-a-date"},
                new String[]{"from", "2026-02-30"},
                new String[]{"to", "2026-10-31"});
        for (String[] parameter : invalidSummaryParameters) {
            assertInvalidQuery(get("/api/dashboard/summary").param(parameter[0], parameter[1]));
        }
        assertInvalidQuery(get("/api/dashboard/summary")
                .param("from", "2026-10-02").param("to", "2026-10-01"));
        assertInvalidQuery(get("/api/dashboard/summary")
                .param("from", "2025-01-01").param("to", "2026-01-02"));

        List<String[]> invalidRecentParameters = List.of(
                new String[]{"view", "UNKNOWN"},
                new String[]{"page", "not-a-number"},
                new String[]{"page", "-1"},
                new String[]{"page", "1001"},
                new String[]{"size", "0"},
                new String[]{"size", "51"});
        for (String[] parameter : invalidRecentParameters) {
            assertInvalidQuery(get("/api/dashboard/recent").param(parameter[0], parameter[1]));
        }

        verifyNoInteractions(summaryService, recentService);
    }

    @Test
    void unexpectedServiceFailureIsSanitizedAndNotCached() throws Exception {
        when(summaryService.get(USER_ID, null, null))
                .thenThrow(new IllegalStateException("SQL=private-query email=private@example.com"));
        authenticate();

        String body = mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("The request could not be completed."))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("private-query"));
        assertFalse(body.contains("private@example.com"));
        verify(summaryService).get(USER_ID, null, null);
        verifyNoInteractions(recentService);
    }

    private void authenticate() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
        SecurityContextHolder.setContext(securityContext);
    }

    private void assertInvalidQuery(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        var response = mockMvc.perform(request).andReturn().getResponse();
        String body = response.getContentAsString();
        assertEquals(400, response.getStatus(), body);
        assertTrue(response.getContentType().contains("application/json"));
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("INVALID_DASHBOARD_QUERY", objectMapper.readTree(body).get("code").asString());
        assertEquals("The dashboard query is invalid.", objectMapper.readTree(body).get("message").asString());
        assertFalse(body.contains("not-a-date"));
        assertFalse(body.contains("UNKNOWN"));
        assertFalse(body.contains("not-a-number"));
    }

    private static DashboardSummary dashboardSummary() {
        return new DashboardSummary(
                new InsightsPeriod(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-01")),
                new DashboardAggregateCounts(2, 1, 1, 1),
                new BigDecimal("50.00"),
                3,
                List.of(new CurrencyAmount("USD", new BigDecimal("125.0000"), 1)),
                List.of(new CurrencyAmount("USD", new BigDecimal("5.0000"), 1)),
                new DashboardDataQualityCounts(1, 1, 1),
                List.of(new DashboardTopSupplier(new SupplierKey("acme supplies"), "ACME Supplies", 1)),
                List.of(new DiscrepancyTypeFrequency(DiscrepancyType.TOTAL_MISMATCH, 2)));
    }

    private static AnalysisHistoryItem historyItem() {
        return new AnalysisHistoryItem(
                ANALYSIS_ID,
                AnalysisStatus.COMPLETED,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisReviewStatus.PENDING,
                null,
                "ACME Supplies",
                "PURCHASE_ORDER",
                "PO-104",
                "INV-205",
                "USD",
                new BigDecimal("120.0000"),
                new BigDecimal("125.0000"),
                new BigDecimal("5.0000"),
                CREATED_AT,
                COMPLETED_AT,
                COMPLETED_AT);
    }

    private Set<String> jsonFieldNames(String body) throws Exception {
        return jsonFieldNames(objectMapper.readTree(body));
    }

    private static Set<String> jsonFieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
