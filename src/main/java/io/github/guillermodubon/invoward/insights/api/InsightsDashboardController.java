package io.github.guillermodubon.invoward.insights.api;

import io.github.guillermodubon.invoward.insights.api.exception.InvalidDashboardQueryException;
import io.github.guillermodubon.invoward.insights.api.model.DashboardRecentResponse;
import io.github.guillermodubon.invoward.insights.api.model.DashboardSummaryResponse;
import io.github.guillermodubon.invoward.insights.application.model.DashboardRecentQuery;
import io.github.guillermodubon.invoward.insights.application.service.GetDashboardSummaryService;
import io.github.guillermodubon.invoward.insights.application.service.InsightsPeriodFactory;
import io.github.guillermodubon.invoward.insights.application.service.ListDashboardRecentService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

/** Registered-account-only HTTP endpoints for read-only dashboard queries. */
@RestController
@RequestMapping(path = "/api/dashboard", produces = MediaType.APPLICATION_JSON_VALUE)
public class InsightsDashboardController {

    private final GetDashboardSummaryService getDashboardSummaryService;
    private final ListDashboardRecentService listDashboardRecentService;
    private final InsightsPeriodFactory insightsPeriodFactory;

    public InsightsDashboardController(
            GetDashboardSummaryService getDashboardSummaryService,
            ListDashboardRecentService listDashboardRecentService,
            InsightsPeriodFactory insightsPeriodFactory) {
        this.getDashboardSummaryService = Objects.requireNonNull(
                getDashboardSummaryService, "getDashboardSummaryService is required");
        this.listDashboardRecentService = Objects.requireNonNull(
                listDashboardRecentService, "listDashboardRecentService is required");
        this.insightsPeriodFactory = Objects.requireNonNull(
                insightsPeriodFactory, "insightsPeriodFactory is required");
    }

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponse> summary(
            @AuthenticationPrincipal(expression = "userId()") UUID registeredUserId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        try {
            insightsPeriodFactory.create(fromDate, toDate);
        } catch (IllegalArgumentException exception) {
            throw new InvalidDashboardQueryException();
        }
        DashboardSummaryResponse response = DashboardSummaryResponse.from(
                getDashboardSummaryService.get(registeredUserId, fromDate, toDate));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/recent")
    public ResponseEntity<DashboardRecentResponse> recent(
            @AuthenticationPrincipal(expression = "userId()") UUID registeredUserId,
            @RequestParam(name = "view", required = false) String view,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size) {
        DashboardRecentQuery query;
        try {
            query = DashboardRecentQuery.of(view, parseInteger(page), parseInteger(size));
        } catch (IllegalArgumentException exception) {
            throw new InvalidDashboardQueryException();
        }
        DashboardRecentResponse response = DashboardRecentResponse.from(
                listDashboardRecentService.list(registeredUserId, query));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException exception) {
            throw new InvalidDashboardQueryException();
        }
    }

    private static Integer parseInteger(String value) {
        return value == null ? null : Integer.valueOf(value.strip());
    }
}
