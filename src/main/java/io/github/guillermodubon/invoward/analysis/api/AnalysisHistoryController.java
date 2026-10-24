package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.api.model.AnalysisHistoryResponse;
import io.github.guillermodubon.invoward.analysis.application.exception.InvalidAnalysisHistoryQueryException;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistoryQuery;
import io.github.guillermodubon.invoward.analysis.application.model.AnalysisHistorySort;
import io.github.guillermodubon.invoward.analysis.application.model.HistoryDiscrepancyType;
import io.github.guillermodubon.invoward.analysis.application.service.ListAnalysisHistoryService;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReconciliationStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisReviewStatus;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisStatus;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Registered-user-only HTTP boundary for paginated Analysis history. */
@RestController
@RequestMapping("/api/analyses")
public class AnalysisHistoryController {

    private final ListAnalysisHistoryService listAnalysisHistoryService;

    public AnalysisHistoryController(ListAnalysisHistoryService listAnalysisHistoryService) {
        this.listAnalysisHistoryService = Objects.requireNonNull(listAnalysisHistoryService);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisHistoryResponse> list(
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "processingStatus", required = false) String processingStatus,
            @RequestParam(name = "reviewStatus", required = false) String reviewStatus,
            @RequestParam(name = "supplier", required = false) String supplier,
            @RequestParam(name = "discrepancyType", required = false) String discrepancyType,
            @RequestParam(name = "currency", required = false) String currency,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "minDifference", required = false) String minDifference,
            @RequestParam(name = "maxDifference", required = false) String maxDifference,
            @RequestParam(name = "sort", required = false) String sort) {
        AnalysisHistoryQuery query = parseQuery(
                page, size, search, status, processingStatus, reviewStatus, supplier, discrepancyType,
                currency, from, to, minDifference, maxDifference, sort);
        AnalysisHistoryResponse response = AnalysisHistoryResponse.from(
                listAnalysisHistoryService.list(Objects.requireNonNull(authenticatedIdentity).userId(), query));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    private AnalysisHistoryQuery parseQuery(
            String page,
            String size,
            String search,
            String status,
            String processingStatus,
            String reviewStatus,
            String supplier,
            String discrepancyType,
            String currency,
            String from,
            String to,
            String minDifference,
            String maxDifference,
            String sort) {
        try {
            return AnalysisHistoryQuery.of(
                    parseInteger(page),
                    parseInteger(size),
                    search,
                    parseEnum(status, AnalysisReconciliationStatus.class),
                    parseEnum(processingStatus, AnalysisStatus.class),
                    parseEnum(reviewStatus, AnalysisReviewStatus.class),
                    supplier,
                    parseEnum(discrepancyType, HistoryDiscrepancyType.class),
                    currency,
                    parseDate(from),
                    parseDate(to),
                    parseDecimal(minDifference),
                    parseDecimal(maxDifference),
                    AnalysisHistorySort.parse(sort));
        } catch (InvalidAnalysisHistoryQueryException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new InvalidAnalysisHistoryQueryException();
        }
    }

    private Integer parseInteger(String value) {
        return value == null ? null : Integer.valueOf(value.strip());
    }

    private LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException exception) {
            throw new InvalidAnalysisHistoryQueryException();
        }
    }

    private BigDecimal parseDecimal(String value) {
        return value == null ? null : new BigDecimal(value.strip());
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType) {
        return value == null ? null : Enum.valueOf(enumType, value.strip());
    }
}
