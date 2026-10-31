package io.github.guillermodubon.invoward.sharing.api;

import io.github.guillermodubon.invoward.sharing.api.model.PublicSharedReportResponse;
import io.github.guillermodubon.invoward.sharing.application.model.PublicSharedReport;
import io.github.guillermodubon.invoward.sharing.application.service.GetPublicSharedReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Read-only public endpoint for a validated share-link capability. */
@RestController
@RequestMapping("/api/public/reports")
public class PublicSharedReportController {

    private final GetPublicSharedReportService getPublicSharedReportService;
    private final PublicSharedReportResponseMapper responseMapper;

    public PublicSharedReportController(
            GetPublicSharedReportService getPublicSharedReportService,
            PublicSharedReportResponseMapper responseMapper) {
        this.getPublicSharedReportService = Objects.requireNonNull(getPublicSharedReportService);
        this.responseMapper = Objects.requireNonNull(responseMapper);
    }

    @GetMapping(path = "/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PublicSharedReportResponse> get(@PathVariable String token) {
        PublicSharedReport report = getPublicSharedReportService.get(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_JSON)
                .body(responseMapper.map(report));
    }
}
