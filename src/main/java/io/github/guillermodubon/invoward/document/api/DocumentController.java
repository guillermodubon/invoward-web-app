package io.github.guillermodubon.invoward.document.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.document.api.model.DocumentDetailResponse;
import io.github.guillermodubon.invoward.document.api.model.DocumentErrorResponse;
import io.github.guillermodubon.invoward.document.api.model.DocumentResponse;
import io.github.guillermodubon.invoward.document.application.exception.DocumentUploadValidationException;
import io.github.guillermodubon.invoward.document.application.model.DocumentDownload;
import io.github.guillermodubon.invoward.document.application.model.IncomingDocumentUpload;
import io.github.guillermodubon.invoward.document.application.service.GetDocumentService;
import io.github.guillermodubon.invoward.document.application.service.ListDocumentsService;
import io.github.guillermodubon.invoward.document.application.service.UploadDocumentService;
import io.github.guillermodubon.invoward.document.domain.Document;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** HTTP boundary for uploading an original document to an existing Analysis. */
@RestController
@RequestMapping("/api/analyses/{analysisId}/documents")
public class DocumentController {

    private static final DocumentErrorResponse INVALID_REQUEST = new DocumentErrorResponse(
            "INVALID_DOCUMENT_REQUEST", "The document upload request is invalid.");

    private final UploadDocumentService uploadDocumentService;
    private final ListDocumentsService listDocumentsService;
    private final GetDocumentService getDocumentService;
    private final AnalysisRequestOwnerResolver ownerResolver;

    public DocumentController(
            UploadDocumentService uploadDocumentService,
            ListDocumentsService listDocumentsService,
            GetDocumentService getDocumentService,
            AnalysisRequestOwnerResolver ownerResolver) {
        this.uploadDocumentService = Objects.requireNonNull(uploadDocumentService);
        this.listDocumentsService = Objects.requireNonNull(listDocumentsService);
        this.getDocumentService = Objects.requireNonNull(getDocumentService);
        this.ownerResolver = Objects.requireNonNull(ownerResolver);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DocumentResponse>> list(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        List<DocumentResponse> documents = listDocumentsService.list(analysisId, owner).stream()
                .map(DocumentResponse::from)
                .toList();
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok(documents);
    }

    @GetMapping(path = "/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentDetailResponse> get(
            @PathVariable UUID analysisId,
            @PathVariable UUID documentId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            HttpServletRequest request) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, request)
                .orElseThrow(AnalysisNotFoundException::new);
        DocumentDownload document = getDocumentService.get(analysisId, documentId, owner);
        ownerResolver.recordSuccessfulActivity(owner);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(DocumentDetailResponse.from(document));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> upload(
            @PathVariable UUID analysisId,
            @AuthenticationPrincipal AuthenticatedIdentity authenticatedIdentity,
            MultipartHttpServletRequest multipartRequest) {
        AnalysisOwner owner = ownerResolver.resolveForRead(authenticatedIdentity, multipartRequest)
                .orElseThrow(AnalysisNotFoundException::new);

        DocumentRole role = resolveRole(multipartRequest.getParameterValues("role"));
        MultipartFile file = resolveSingleFile(multipartRequest.getMultiFileMap());
        if (role == null || file == null) {
            return ResponseEntity.badRequest().body(INVALID_REQUEST);
        }

        try (var content = file.getInputStream()) {
            IncomingDocumentUpload incoming = new IncomingDocumentUpload(
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), content);
            Document uploaded = uploadDocumentService.upload(analysisId, owner, role, incoming);
            ownerResolver.recordSuccessfulActivity(owner);

            URI location = URI.create(
                    "/api/analyses/" + analysisId + "/documents/" + uploaded.id());
            return ResponseEntity.created(location)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(DocumentResponse.from(uploaded));
        } catch (IOException exception) {
            throw new DocumentUploadValidationException(
                    DocumentUploadValidationException.Failure.DOCUMENT_INVALID);
        }
    }

    private static DocumentRole resolveRole(String[] values) {
        if (values == null || values.length != 1) {
            return null;
        }
        try {
            return DocumentRole.valueOf(values[0]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static MultipartFile resolveSingleFile(MultiValueMap<String, MultipartFile> files) {
        int totalFiles = files.values().stream().mapToInt(List::size).sum();
        List<MultipartFile> namedFiles = files.get("file");
        if (totalFiles != 1 || namedFiles == null || namedFiles.size() != 1) {
            return null;
        }
        return namedFiles.getFirst();
    }
}
