package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.api.model.AnalysisReviewStatusResponse;
import io.github.guillermodubon.invoward.analysis.api.model.ChangeAnalysisReviewStatusRequest;
import io.github.guillermodubon.invoward.analysis.application.service.ChangeAnalysisReviewStatusService;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** Registered-user-only HTTP boundary for Analysis review status. */
@RestController
@RequestMapping("/api/analyses/{analysisId}/review-status")
public class AnalysisReviewController {

    private final ChangeAnalysisReviewStatusService changeReviewStatusService;

    public AnalysisReviewController(ChangeAnalysisReviewStatusService changeReviewStatusService) {
        this.changeReviewStatusService = Objects.requireNonNull(changeReviewStatusService);
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisReviewStatusResponse> changeReviewStatus(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity identity,
            @RequestBody ChangeAnalysisReviewStatusRequest request) {
        var result = changeReviewStatusService.change(
                analysisId,
                Objects.requireNonNull(identity).userId(),
                request.reviewStatus());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AnalysisReviewStatusResponse.from(result));
    }
}
