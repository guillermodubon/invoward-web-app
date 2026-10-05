package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.extraction.application.service.DetectDocumentTypesService;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for owner-scoped type detection of an Analysis's uploaded documents. */
@RestController
@RequestMapping("/api/analyses/{analysisId}/documents")
public class DocumentTypeDetectionController {

    private final DetectDocumentTypesService detectDocumentTypesService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public DocumentTypeDetectionController(
            DetectDocumentTypesService detectDocumentTypesService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.detectDocumentTypesService = Objects.requireNonNull(detectDocumentTypesService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
    }

    @PostMapping(path = "/detect-types", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DetectedDocumentTypeResponse>> detectTypes(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        List<DetectedDocumentTypeResponse> response = detectDocumentTypesService.detect(analysisId, owner).stream()
                .map(DetectedDocumentTypeResponse::from)
                .toList();
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
