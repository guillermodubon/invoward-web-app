package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryItem;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryPage;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryQuery;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistorySort;
import io.github.guillermodubon.invoward.analysis.application.model.HistoryDiscrepancyType;
import io.github.guillermodubon.invoward.analysis.application.service.ListAnalysisHistoryService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import io.github.guillermodubon.invoward.identity.application.model.CurrentAccount;
import io.github.guillermodubon.invoward.identity.domain.UserStatus;
import jakarta.servlet.http.Cookie;
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
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AnalysisHistoryControllerTest {

    private static final UUID USER_ID = UUID.fromString("2ef04750-51db-4aa8-bb60-04ed2e6dd343");
    private static final UUID ANALYSIS_ID = UUID.fromString("6e30feda-e97d-419a-b7c8-f179b5a3cc83");
    private static final Instant CREATED_AT = Instant.parse("2026-09-23T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-23T10:03:00Z");

    private final ListAnalysisHistoryService historyService = mock(ListAnalysisHistoryService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthenticatedIdentity identity = new CurrentAccount(
            USER_ID, "Account", "account@example.com", true, UserStatus.ACTIVE);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new AnalysisHistoryController(historyService))
                .setControllerAdvice(new AnalysisExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultRequestReturnsEmptyPageWithStableMetadataAndNoStore() throws Exception {
        AnalysisHistoryQuery query = AnalysisHistoryQuery.defaults();
        when(historyService.list(USER_ID, query)).thenReturn(new AnalysisHistoryPage(List.of(), 0, 20, 0));
        authenticate();

        String body = mockMvc.perform(get("/api/analyses"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(false))
                .andReturn().getResponse().getContentAsString();

        assertEquals(Set.of("content", "page", "size", "totalElements", "totalPages", "hasNext", "hasPrevious"),
                jsonFieldNames(body));
        verify(historyService).list(USER_ID, query);
    }

    @Test
    void parsesTypedFiltersUsesAuthenticatedPrincipalAndReturnsOnlySafeHistoryFields() throws Exception {
        AnalysisHistoryQuery query = AnalysisHistoryQuery.of(
                1,
                1,
                " dell ",
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisStatus.COMPLETED,
                AnalysisReviewStatus.PENDING,
                " Vendor ",
                HistoryDiscrepancyType.UNIT_PRICE_MISMATCH,
                "usd",
                LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-30"),
                new BigDecimal("10.00"),
                new BigDecimal("20"),
                AnalysisHistorySort.SUPPLIER_NAME_ASC);
        AnalysisHistoryItem item = historyItem();
        when(historyService.list(USER_ID, query)).thenReturn(new AnalysisHistoryPage(List.of(item), 1, 1, 3));
        authenticate();
        UUID untrustedQueryUserId = UUID.fromString("e06e6f16-40ba-44f5-a170-9a8e4125b4fe");

        String body = mockMvc.perform(get("/api/analyses")
                        .param("page", "1")
                        .param("size", "1")
                        .param("search", " dell ")
                        .param("status", "REVIEW_REQUIRED")
                        .param("processingStatus", "COMPLETED")
                        .param("reviewStatus", "PENDING")
                        .param("supplier", " Vendor ")
                        .param("discrepancyType", "UNIT_PRICE_MISMATCH")
                        .param("currency", "usd")
                        .param("from", "2026-09-01")
                        .param("to", "2026-09-30")
                        .param("minDifference", "10.00")
                        .param("maxDifference", "20")
                        .param("sort", "supplierName,asc")
                        // This parameter is intentionally not part of the endpoint contract.
                        .param("userId", untrustedQueryUserId.toString())
                        .cookie(new Cookie("INVOWARD_GUEST", UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.content[0].id").value(ANALYSIS_ID.toString()))
                .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.content[0].reconciliationStatus").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.content[0].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].reviewedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].supplierName").value("ACME Supplies"))
                .andExpect(jsonPath("$.content[0].referenceType").value("QUOTE"))
                .andExpect(jsonPath("$.content[0].referenceNumber").value("Q-2026-0412"))
                .andExpect(jsonPath("$.content[0].invoiceNumber").value("INV-2026-0412"))
                .andExpect(jsonPath("$.content[0].currency").value("USD"))
                .andExpect(jsonPath("$.content[0].referenceTotal").value(2450.0000))
                .andExpect(jsonPath("$.content[0].invoicedTotal").value(2610.0000))
                .andExpect(jsonPath("$.content[0].difference").value(160.0000))
                .andExpect(jsonPath("$.content[0].createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.content[0].updatedAt").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$.content[0].completedAt").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.hasPrevious").value(true))
                .andReturn().getResponse().getContentAsString();

        assertEquals(Set.of("content", "page", "size", "totalElements", "totalPages", "hasNext", "hasPrevious"),
                jsonFieldNames(body));
        assertEquals(Set.of("id", "status", "reconciliationStatus", "reviewStatus", "reviewedAt",
                        "supplierName", "referenceType", "referenceNumber", "invoiceNumber", "currency",
                        "referenceTotal", "invoicedTotal", "difference", "createdAt", "updatedAt", "completedAt"),
                jsonFieldNames(objectMapper.readTree(body).get("content").get(0)));
        assertFalse(body.contains("userId"));
        assertFalse(body.contains(USER_ID.toString()));
        assertFalse(body.contains(untrustedQueryUserId.toString()));
        assertFalse(body.contains("guestSessionId"));
        assertFalse(body.contains("noteText"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        verify(historyService).list(USER_ID, query);
    }

    @Test
    void invalidQuerySyntaxAndValuesReturnSafeHistoryErrorWithoutCallingService() throws Exception {
        List<String[]> invalidParameters = List.of(
                new String[]{"page", "not-a-number"},
                new String[]{"page", "-1"},
                new String[]{"page", "1001"},
                new String[]{"size", "101"},
                new String[]{"size", "0"},
                new String[]{"status", "NOT_A_RECONCILIATION_STATUS"},
                new String[]{"processingStatus", "NOT_A_PROCESSING_STATUS"},
                new String[]{"reviewStatus", "NOT_A_REVIEW_STATUS"},
                new String[]{"discrepancyType", "NOT_A_DISCREPANCY"},
                new String[]{"from", "2026-02-30"},
                new String[]{"to", "999999999-01-01"},
                new String[]{"minDifference", "not-a-number"},
                new String[]{"minDifference", "-0.01"},
                new String[]{"minDifference", "1.00001"},
                new String[]{"currency", "US"},
                new String[]{"sort", "supplierName,raw-sql"});

        authenticate();
        for (String[] parameter : invalidParameters) {
            String body = mockMvc.perform(get("/api/analyses").param(parameter[0], parameter[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(jsonPath("$.code").value("INVALID_HISTORY_QUERY"))
                    .andExpect(jsonPath("$.message").value("The history query is invalid."))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains(parameter[1]));
        }

        mockMvc.perform(get("/api/analyses").param("minDifference", "20").param("maxDifference", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HISTORY_QUERY"));
        mockMvc.perform(get("/api/analyses").param("from", "2026-09-30").param("to", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HISTORY_QUERY"));

        verifyNoInteractions(historyService);
    }

    private void authenticate() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of()));
        SecurityContextHolder.setContext(securityContext);
    }

    private static AnalysisHistoryItem historyItem() {
        return new AnalysisHistoryItem(
                ANALYSIS_ID,
                AnalysisStatus.COMPLETED,
                AnalysisReconciliationStatus.REVIEW_REQUIRED,
                AnalysisReviewStatus.PENDING,
                null,
                "ACME Supplies",
                "QUOTE",
                "Q-2026-0412",
                "INV-2026-0412",
                "USD",
                new BigDecimal("2450.0000"),
                new BigDecimal("2610.0000"),
                new BigDecimal("160.0000"),
                CREATED_AT,
                UPDATED_AT,
                UPDATED_AT);
    }

    private Set<String> jsonFieldNames(String body) throws Exception {
        return jsonFieldNames(objectMapper.readTree(body));
    }

    private Set<String> jsonFieldNames(JsonNode json) {
        Set<String> names = new HashSet<>();
        json.propertyNames().forEach(names::add);
        return names;
    }
}
