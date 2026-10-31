package io.github.guillermodubon.invoward.sharing.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.sharing.api.model.CreateShareLinkRequest;
import io.github.guillermodubon.invoward.sharing.api.model.CreatedShareLinkResponse;
import io.github.guillermodubon.invoward.sharing.api.model.ShareLinkPageResponse;
import io.github.guillermodubon.invoward.sharing.application.model.CreatedShareLink;
import io.github.guillermodubon.invoward.sharing.application.model.ShareLinkListing;
import io.github.guillermodubon.invoward.sharing.application.service.CreateShareLinkService;
import io.github.guillermodubon.invoward.sharing.application.service.ListShareLinksService;
import io.github.guillermodubon.invoward.sharing.application.service.RevokeShareLinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for owner-authorized share-link management. */
@RestController
@RequestMapping("/api/analyses/{analysisId}/share-links")
public class ShareLinkController {

    private final CreateShareLinkService createShareLinkService;
    private final ListShareLinksService listShareLinksService;
    private final RevokeShareLinkService revokeShareLinkService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public ShareLinkController(
            CreateShareLinkService createShareLinkService,
            ListShareLinksService listShareLinksService,
            RevokeShareLinkService revokeShareLinkService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.createShareLinkService = Objects.requireNonNull(createShareLinkService);
        this.listShareLinksService = Objects.requireNonNull(listShareLinksService);
        this.revokeShareLinkService = Objects.requireNonNull(revokeShareLinkService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreatedShareLinkResponse> create(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request,
            @Valid @RequestBody CreateShareLinkRequest createRequest) {
        AnalysisOwner owner = ownerResolver.resolveForReadPrincipal(principal, request)
                .orElseThrow(AnalysisNotFoundException::new);
        CreatedShareLink created = createShareLinkService.create(
                analysisId, owner, createRequest.toExpiryChoice());
        ownerResolver.recordSuccessfulActivity(owner);

        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(CreatedShareLinkResponse.from(created));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ShareLinkPageResponse> list(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        AnalysisOwner owner = ownerResolver.resolveForReadPrincipal(principal, request)
                .orElseThrow(AnalysisNotFoundException::new);
        ShareLinkListing listing = listShareLinksService.list(analysisId, owner, page, size);
        ownerResolver.recordSuccessfulActivity(owner);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(ShareLinkPageResponse.from(listing));
    }

    @DeleteMapping("/{shareId}")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID analysisId,
            @PathVariable UUID shareId,
            @AuthenticationPrincipal Object principal,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForReadPrincipal(principal, request)
                .orElseThrow(AnalysisNotFoundException::new);
        revokeShareLinkService.revoke(analysisId, owner, shareId);
        ownerResolver.recordSuccessfulActivity(owner);

        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
