package io.github.guillermodubon.invoward.sharing.api;

import io.github.guillermodubon.invoward.analysis.application.exception.AnalysisNotFoundException;
import io.github.guillermodubon.invoward.sharing.api.model.ShareLinkErrorResponse;
import io.github.guillermodubon.invoward.sharing.application.exception.InvalidShareLinkQueryException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareExpiryUnavailableException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkCreationException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkLimitReachedException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkNotFoundException;
import io.github.guillermodubon.invoward.sharing.application.exception.ShareLinkNotShareableException;
import io.github.guillermodubon.invoward.sharing.application.exception.PublicSharedReportNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps Sharing API failures to stable responses without exposing tokens or persistence details. */
@RestControllerAdvice(basePackageClasses = ShareLinkController.class)
public class ShareLinkExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ShareLinkErrorResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_SHARE_LINK_REQUEST",
                "The share-link request is invalid.");
    }

    @ExceptionHandler(InvalidShareLinkQueryException.class)
    public ResponseEntity<ShareLinkErrorResponse> handleInvalidQuery(InvalidShareLinkQueryException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_SHARE_LINK_QUERY",
                "The share-link query is invalid.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ShareLinkErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        if ("page".equals(exception.getName()) || "size".equals(exception.getName())) {
            return handleInvalidQuery(new InvalidShareLinkQueryException());
        }
        return handleInvalidRequest(exception);
    }

    @ExceptionHandler(AnalysisNotFoundException.class)
    public ResponseEntity<ShareLinkErrorResponse> handleAnalysisNotFound(AnalysisNotFoundException exception) {
        return notFound("ANALYSIS_NOT_FOUND", "Analysis was not found.");
    }

    @ExceptionHandler(ShareLinkNotFoundException.class)
    public ResponseEntity<ShareLinkErrorResponse> handleShareLinkNotFound(ShareLinkNotFoundException exception) {
        return notFound("SHARE_LINK_NOT_FOUND", "Share link was not found.");
    }

    @ExceptionHandler(PublicSharedReportNotFoundException.class)
    public ResponseEntity<ShareLinkErrorResponse> handlePublicSharedReportNotFound(
            PublicSharedReportNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ShareLinkErrorResponse("SHARE_LINK_NOT_FOUND", "Shared report was not found."));
    }

    @ExceptionHandler(ShareLinkLimitReachedException.class)
    public ResponseEntity<ShareLinkErrorResponse> handleLimitReached(ShareLinkLimitReachedException exception) {
        return response(HttpStatus.CONFLICT, "SHARE_LINK_LIMIT_REACHED",
                "The active share-link limit has been reached.");
    }

    @ExceptionHandler(ShareLinkNotShareableException.class)
    public ResponseEntity<ShareLinkErrorResponse> handleNotShareable(ShareLinkNotShareableException exception) {
        return response(HttpStatus.CONFLICT, "ANALYSIS_NOT_SHAREABLE",
                "Analysis is not ready to be shared.");
    }

    @ExceptionHandler(ShareExpiryUnavailableException.class)
    public ResponseEntity<ShareLinkErrorResponse> handleExpiryUnavailable(
            ShareExpiryUnavailableException exception) {
        return response(HttpStatus.CONFLICT, "SHARE_EXPIRY_UNAVAILABLE",
                "The share-link expiration is no longer available.");
    }

    @ExceptionHandler(ShareLinkCreationException.class)
    public ResponseEntity<ShareLinkErrorResponse> handleCreationFailure(ShareLinkCreationException exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "SHARE_LINK_CREATION_FAILED",
                "The share link could not be created.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ShareLinkErrorResponse> handleUnexpectedFailure(
            Exception exception, HttpServletRequest request) {
        if (isCreateRequest(request)) {
            return internalFailure("SHARE_LINK_CREATION_FAILED", "The share link could not be created.");
        }
        return internalFailure("SHARE_LINK_OPERATION_FAILED", "The share-link operation could not be completed.");
    }

    private static ResponseEntity<ShareLinkErrorResponse> response(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ShareLinkErrorResponse(code, message));
    }

    private static ResponseEntity<ShareLinkErrorResponse> notFound(String code, String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("Cache-Control", "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ShareLinkErrorResponse(code, message));
    }

    private static ResponseEntity<ShareLinkErrorResponse> internalFailure(String code, String message) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ShareLinkErrorResponse(code, message));
    }

    private static boolean isCreateRequest(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
        }
        return path != null && path.matches("/api/analyses/[^/]+/share-links");
    }
}
