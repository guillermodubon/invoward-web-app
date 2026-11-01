package io.github.guillermodubon.invoward.insights.api;

import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierHistoryQueryException;
import io.github.guillermodubon.invoward.insights.api.exception.InvalidSupplierQueryException;
import io.github.guillermodubon.invoward.insights.api.model.SupplierAnalysisPageResponse;
import io.github.guillermodubon.invoward.insights.api.model.SupplierListResponse;
import io.github.guillermodubon.invoward.insights.api.model.SupplierSummaryResponse;
import io.github.guillermodubon.invoward.insights.application.model.SupplierAnalysisQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierListQuery;
import io.github.guillermodubon.invoward.insights.application.model.SupplierKey;
import io.github.guillermodubon.invoward.insights.application.service.GetSupplierSummaryService;
import io.github.guillermodubon.invoward.insights.application.service.ListSupplierAnalysesService;
import io.github.guillermodubon.invoward.insights.application.service.ListSuppliersService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** Registered-account-only HTTP endpoints for read-only supplier insights. */
@RestController
@RequestMapping(path = "/api/suppliers", produces = MediaType.APPLICATION_JSON_VALUE)
public class SupplierInsightsController {

    private final ListSuppliersService listSuppliersService;
    private final GetSupplierSummaryService getSupplierSummaryService;
    private final ListSupplierAnalysesService listSupplierAnalysesService;

    public SupplierInsightsController(
            ListSuppliersService listSuppliersService,
            GetSupplierSummaryService getSupplierSummaryService,
            ListSupplierAnalysesService listSupplierAnalysesService) {
        this.listSuppliersService = Objects.requireNonNull(listSuppliersService, "listSuppliersService is required");
        this.getSupplierSummaryService = Objects.requireNonNull(
                getSupplierSummaryService, "getSupplierSummaryService is required");
        this.listSupplierAnalysesService = Objects.requireNonNull(
                listSupplierAnalysesService, "listSupplierAnalysesService is required");
    }

    @GetMapping
    public ResponseEntity<SupplierListResponse> suppliers(
            @AuthenticationPrincipal(expression = "userId()") UUID registeredUserId,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "sort", required = false) String sort) {
        SupplierListQuery query;
        try {
            query = SupplierListQuery.of(parseInteger(page), parseInteger(size), search, sort);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSupplierQueryException();
        }

        SupplierListResponse response = SupplierListResponse.from(listSuppliersService.list(registeredUserId, query));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/{supplierKey}/summary")
    public ResponseEntity<SupplierSummaryResponse> summary(
            @AuthenticationPrincipal(expression = "userId()") UUID registeredUserId,
            @PathVariable("supplierKey") String urlDecodedSupplierKey) {
        SupplierKey supplierKey = SupplierKeyQueryValidator.validate(urlDecodedSupplierKey);
        SupplierSummaryResponse response = SupplierSummaryResponse.from(
                getSupplierSummaryService.get(registeredUserId, supplierKey));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/{supplierKey}/analyses")
    public ResponseEntity<SupplierAnalysisPageResponse> analyses(
            @AuthenticationPrincipal(expression = "userId()") UUID registeredUserId,
            @PathVariable("supplierKey") String urlDecodedSupplierKey,
            @RequestParam(name = "processingStatus", required = false) String processingStatus,
            @RequestParam(name = "reviewStatus", required = false) String reviewStatus,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "sort", required = false) String sort) {
        SupplierKey supplierKey = SupplierKeyQueryValidator.validate(urlDecodedSupplierKey);
        SupplierAnalysisQuery query;
        try {
            query = SupplierAnalysisQuery.of(
                    parseEnum(processingStatus, AnalysisStatus.class),
                    parseEnum(reviewStatus, AnalysisReviewStatus.class),
                    parseInteger(page),
                    parseInteger(size),
                    sort);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSupplierHistoryQueryException();
        }

        SupplierAnalysisPageResponse response = SupplierAnalysisPageResponse.from(
                listSupplierAnalysesService.list(registeredUserId, supplierKey, query));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    private static Integer parseInteger(String value) {
        return value == null ? null : Integer.valueOf(value.strip());
    }

    private static <T extends Enum<T>> T parseEnum(String value, Class<T> enumType) {
        return value == null ? null : Enum.valueOf(enumType, value.strip());
    }
}
