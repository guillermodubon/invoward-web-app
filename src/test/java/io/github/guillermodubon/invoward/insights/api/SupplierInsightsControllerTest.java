package io.github.guillermodubon.invoward.insights.api;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import io.github.guillermodubon.invoward.insights.application.exception.SupplierNotFoundException;
import io.github.guillermodubon.invoward.insights.application.model.CurrencyAmount;
import io.github.guillermodubon.invoward.insights.application.model.InsightsPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAggregateCounts;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListItem;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListPage;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierSummary;
import io.github.guillermodubon.invoward.insights.application.service.GetSupplierSummaryService;
import io.github.guillermodubon.invoward.insights.application.service.ListSupplierAnalysesService;
import io.github.guillermodubon.invoward.insights.application.service.ListSuppliersService;
import io.github.guillermodubon.invoward.reconciliation.domain.DiscrepancyType;
import io.github.guillermodubon.invoward.insights.application.model.DiscrepancyTypeFrequency;
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

class SupplierInsightsControllerTest {

    private static final UUID USER_ID = UUID.fromString("ad98944a-d589-4b10-9eb0-3f3203ce1f0b");
    private static final UUID ANALYSIS_ID = UUID.fromString("b9d32f11-4d55-4a34-8cb2-20e47486dc9f");
    private static final Instant CREATED_AT = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-20T10:05:00Z");
    private static final SupplierKey SUPPLIER_KEY = new SupplierKey("acme supplies");

    private final ListSuppliersService listSuppliersService = mock(ListSuppliersService.class);
    private final GetSupplierSummaryService getSupplierSummaryService = mock(GetSupplierSummaryService.class);
    private final ListSupplierAnalysesService listSupplierAnalysesService = mock(ListSupplierAnalysesService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthenticatedIdentity identity = new CurrentAccount(
            USER_ID, "Registered User", "private@example.com", true, UserStatus.ACTIVE);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new SupplierInsightsController(
                        listSuppliersService, getSupplierSummaryService, listSupplierAnalysesService))
                .setControllerAdvice(new InsightsDashboardExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        authenticate();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supplierListBindsTypedQueryAndReturnsOnlySafePageProjection() throws Exception {
        SupplierListQuery query = SupplierListQuery.of(2, 3, "Acme", "supplierName,asc");
        var item = new SupplierListItem(
                SUPPLIER_KEY,
                "ACME Supplies",
                2,
                1,
                new BigDecimal("50.00"),
                List.of(new CurrencyAmount("USD", new BigDecimal("125.0000"), 2)),
                List.of(new CurrencyAmount("USD", new BigDecimal("5.0000"), 1)));
        when(listSuppliersService.list(USER_ID, query)).thenReturn(
                new SupplierListPage(new InsightsPage<>(List.of(item), 2, 3, 7)));

        String body = mockMvc.perform(get("/api/suppliers")
                        .param("page", "2")
                        .param("size", "3")
                        .param("search", "Acme")
                        .param("sort", "supplierName,asc")
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.content[0].supplierKey").value("acme supplies"))
                .andExpect(jsonPath("$.content[0].completedAnalyses").value(2))
                .andExpect(jsonPath("$.content[0].totalInvoicedByCurrency[0].currency").value("USD"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andReturn().getResponse().getContentAsString();

        assertEquals(Set.of("content", "page", "size", "totalElements", "totalPages", "hasNext", "hasPrevious"),
                fieldNames(body));
        assertEquals(Set.of("supplierKey", "supplierName", "completedAnalyses", "analysesWithDifferences",
                        "differenceRatePercent", "totalInvoicedByCurrency", "potentialDifferencesByCurrency"),
                fieldNames(objectMapper.readTree(body).get("content").get(0)));
        assertFalse(body.contains(USER_ID.toString()));
        assertFalse(body.contains("private@example.com"));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("note"));
        assertFalse(body.contains("token"));
        assertFalse(body.contains("storageKey"));
        verify(listSuppliersService).list(USER_ID, query);
        verifyNoInteractions(getSupplierSummaryService, listSupplierAnalysesService);
        verifyNoMoreInteractions(listSuppliersService);
    }

    @Test
    void supplierSummaryReturnsBoundedPublicFieldsForTheAuthenticatedOwner() throws Exception {
        SupplierSummary summary = supplierSummary();
        when(getSupplierSummaryService.get(USER_ID, SUPPLIER_KEY)).thenReturn(summary);

        String body = mockMvc.perform(get("/api/suppliers/{supplierKey}/summary", "acme supplies")
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.supplierKey").value("acme supplies"))
                .andExpect(jsonPath("$.completedAnalyses").value(1))
                .andExpect(jsonPath("$.commonDiscrepancyTypes[0].type").value("TOTAL_MISMATCH"))
                .andExpect(jsonPath("$.recentAnalyses[0].invoiceNumber").value("INV-205"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(Set.of("supplierKey", "supplierName", "completedAnalyses", "matchedAnalyses",
                        "analysesWithDifferences", "differenceRatePercent", "totalInvoicedByCurrency",
                        "potentialDifferencesByCurrency", "differenceAmountUnavailableCount", "missingCurrencyCount",
                        "commonDiscrepancyTypes", "recentAnalyses"), fieldNames(body));
        assertEquals(Set.of("id", "invoiceNumber", "status", "reconciliationStatus", "reviewStatus",
                        "createdAt", "completedAt"),
                fieldNames(objectMapper.readTree(body).get("recentAnalyses").get(0)));
        assertFalse(body.contains(USER_ID.toString()));
        assertFalse(body.contains("private@example.com"));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("resolutionNote"));
        assertFalse(body.contains("shareToken"));
        verify(getSupplierSummaryService).get(USER_ID, SUPPLIER_KEY);
        verifyNoInteractions(listSuppliersService, listSupplierAnalysesService);
        verifyNoMoreInteractions(getSupplierSummaryService);
    }

    @Test
    void supplierAnalysisHistoryBindsFiltersAndUsesTheSafePagedAnalysisProjection() throws Exception {
        SupplierAnalysisQuery query = SupplierAnalysisQuery.of(
                AnalysisStatus.COMPLETED, AnalysisReviewStatus.REVIEWED, 1, 1, "completedAt,asc");
        when(listSupplierAnalysesService.list(USER_ID, SUPPLIER_KEY, query)).thenReturn(
                new SupplierAnalysisPage(SUPPLIER_KEY,
                        new InsightsPage<>(List.of(historyItem()), 1, 1, 3)));

        String body = mockMvc.perform(get("/api/suppliers/{supplierKey}/analyses", "acme supplies")
                        .param("processingStatus", "COMPLETED")
                        .param("reviewStatus", "REVIEWED")
                        .param("page", "1")
                        .param("size", "1")
                        .param("sort", "completedAt,asc"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.supplierKey").value("acme supplies"))
                .andExpect(jsonPath("$.content[0].id").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.content[0].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(USER_ID.toString()));
        assertFalse(body.contains("private@example.com"));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("noteText"));
        verify(listSupplierAnalysesService).list(USER_ID, SUPPLIER_KEY, query);
        verifyNoInteractions(listSuppliersService, getSupplierSummaryService);
        verifyNoMoreInteractions(listSupplierAnalysesService);
    }

    @Test
    void malformedQueriesAndUnsafeSupplierKeysReturnStableBadRequestsWithoutServiceAccess() throws Exception {
        assertBadRequest(get("/api/suppliers").param("page", "not-a-number"), "INVALID_SUPPLIER_QUERY");
        assertBadRequest(get("/api/suppliers").param("sort", "id,desc"), "INVALID_SUPPLIER_QUERY");
        assertBadRequest(get("/api/suppliers/acme/analyses").param("processingStatus", "UNKNOWN"),
                "INVALID_SUPPLIER_HISTORY_QUERY");
        assertBadRequest(get("/api/suppliers/acme/analyses").param("size", "101"),
                "INVALID_SUPPLIER_HISTORY_QUERY");
        assertBadRequest(get("/api/suppliers/{supplierKey}/summary", "x".repeat(241)), "INVALID_SUPPLIER_KEY");
        verifyNoInteractions(listSuppliersService, getSupplierSummaryService, listSupplierAnalysesService);
    }

    @Test
    void unknownOrForeignSupplierUsesTheSameOwnerScopedNotFoundContract() throws Exception {
        when(getSupplierSummaryService.get(USER_ID, SUPPLIER_KEY)).thenThrow(new SupplierNotFoundException());
        when(listSupplierAnalysesService.list(
                USER_ID, SUPPLIER_KEY, SupplierAnalysisQuery.of(null, null, null, null, null)))
                .thenThrow(new SupplierNotFoundException());

        mockMvc.perform(get("/api/suppliers/{supplierKey}/summary", "acme supplies"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {"code":"SUPPLIER_NOT_FOUND","message":"Supplier was not found."}
                        """));
        mockMvc.perform(get("/api/suppliers/{supplierKey}/analyses", "acme supplies"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().json("""
                        {"code":"SUPPLIER_NOT_FOUND","message":"Supplier was not found."}
                        """));

        verify(getSupplierSummaryService).get(USER_ID, SUPPLIER_KEY);
        verify(listSupplierAnalysesService).list(
                USER_ID, SUPPLIER_KEY, SupplierAnalysisQuery.of(null, null, null, null, null));
        verifyNoInteractions(listSuppliersService);
        verifyNoMoreInteractions(getSupplierSummaryService);
        verifyNoMoreInteractions(listSupplierAnalysesService);
    }

    @Test
    void onlyTheThreeSupplierRoutesAreMapped() throws Exception {
        mockMvc.perform(get("/api/suppliers/acme"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/suppliers/acme/summary/extra"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/suppliers/acme/analyses/extra"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(listSuppliersService, getSupplierSummaryService, listSupplierAnalysesService);
    }

    @Test
    void unexpectedServiceFailureIsSanitizedAndNotCached() throws Exception {
        when(listSuppliersService.list(USER_ID, SupplierListQuery.of(null, null, null, null)))
                .thenThrow(new IllegalStateException("SQL=private query owner=" + USER_ID));

        String body = mockMvc.perform(get("/api/suppliers"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("private query"));
        assertFalse(body.contains(USER_ID.toString()));
        verify(listSuppliersService).list(USER_ID, SupplierListQuery.of(null, null, null, null));
        verifyNoInteractions(getSupplierSummaryService, listSupplierAnalysesService);
        verifyNoMoreInteractions(listSuppliersService);
    }

    private void authenticate() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
        SecurityContextHolder.setContext(securityContext);
    }

    private void assertBadRequest(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String code)
            throws Exception {
        String body = mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(code, objectMapper.readTree(body).get("code").asString());
        assertFalse(body.contains("not-a-number"));
        assertFalse(body.contains("UNKNOWN"));
    }

    private static SupplierSummary supplierSummary() {
        return new SupplierSummary(
                SUPPLIER_KEY,
                "ACME Supplies",
                new SupplierAggregateCounts(1, 0, 1, 0, 0),
                new BigDecimal("100.00"),
                List.of(new CurrencyAmount("USD", new BigDecimal("125.0000"), 1)),
                List.of(new CurrencyAmount("USD", new BigDecimal("5.0000"), 1)),
                List.of(new DiscrepancyTypeFrequency(DiscrepancyType.TOTAL_MISMATCH, 1)),
                List.of(historyItem()));
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

    private Set<String> fieldNames(String body) throws Exception {
        return fieldNames(objectMapper.readTree(body));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
