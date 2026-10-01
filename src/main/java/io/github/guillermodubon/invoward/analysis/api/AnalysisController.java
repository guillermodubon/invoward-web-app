package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.analysis.api.model.AnalysisDetailResponse;
import io.github.guillermodubon.invoward.analysis.api.model.AnalysisResponse;
import io.github.guillermodubon.invoward.analysis.api.model.AnalysisStatusResponse;
import io.github.guillermodubon.invoward.analysis.api.model.CreateAnalysisRequest;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.application.service.CreateAnalysisService;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisStatusService;
import io.github.guillermodubon.invoward.analysis.domain.Analysis;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for Analysis workspace creation and detail retrieval. */
@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {

    private final CreateAnalysisService createAnalysisService;
    private final GetAnalysisService getAnalysisService;
    private final GetAnalysisStatusService getAnalysisStatusService;
    private final AnalysisRequestOwnerResolver ownerResolver;
    private final GuestSessionCookieSupport guestSessionCookieSupport;

    public AnalysisController(
            CreateAnalysisService createAnalysisService,
            GetAnalysisService getAnalysisService,
            GetAnalysisStatusService getAnalysisStatusService,
            AnalysisRequestOwnerResolver ownerResolver,
            GuestSessionCookieSupport guestSessionCookieSupport) {
        this.createAnalysisService = Objects.requireNonNull(createAnalysisService);
        this.getAnalysisService = Objects.requireNonNull(getAnalysisService);
        this.getAnalysisStatusService = Objects.requireNonNull(getAnalysisStatusService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
        this.guestSessionCookieSupport = Objects.requireNonNull(guestSessionCookieSupport);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisResponse> create(
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            @Valid @RequestBody CreateAnalysisRequest request,
            HttpServletRequest servletRequest) {
        AnalysisOwner owner = ownerResolver.resolveForCreate(authenticatedIdentity, servletRequest);
        Analysis created = createAnalysisService.create(owner, request.toPriceTolerance());
        ownerResolver.recordSuccessfulActivity(owner);

        ResponseEntity.BodyBuilder response = ResponseEntity.accepted()
                .location(URI.create("/api/analyses/" + created.id()));
        if (created.owner() instanceof GuestSessionOwner guestOwner) {
            response.header(HttpHeaders.SET_COOKIE,
                    guestSessionCookieSupport.createGuestSessionCookie(guestOwner).toString());
        }
        return response.body(AnalysisResponse.from(created));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalysisDetailResponse getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest servletRequest) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, servletRequest)
                .orElseThrow(AnalysisNotFoundException::new);
        Analysis analysis = getAnalysisService.get(id, owner);
        ownerResolver.recordSuccessfulActivity(owner);
        return AnalysisDetailResponse.from(analysis);
    }

    @GetMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalysisStatusResponse getStatus(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest servletRequest) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, servletRequest)
                .orElseThrow(AnalysisNotFoundException::new);
        var status = getAnalysisStatusService.getStatus(id, owner);
        ownerResolver.recordSuccessfulActivity(owner);
        return AnalysisStatusResponse.from(status);
    }
}
