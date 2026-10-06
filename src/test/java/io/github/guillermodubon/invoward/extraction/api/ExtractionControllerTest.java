package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.analysis.api.AnalysisRequestOwnerResolver;
import io.github.guillermodubon.invoward.analysis.domain.AnalysisOwner;
import io.github.guillermodubon.invoward.analysis.domain.GuestSessionOwner;
import io.github.guillermodubon.invoward.document.application.exception.DocumentStorageException;
import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.ConfirmedDocumentTypeInvalidException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionAlreadyConfirmedException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentTypesNotDetectedException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionNotFoundException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionConflictException;
import io.github.guillermodubon.invoward.extraction.application.exception.ExtractionLockedException;
import io.github.guillermodubon.invoward.extraction.application.exception.InvalidExtractionReviewException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReview;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionReviewUpdate;
import io.github.guillermodubon.invoward.extraction.application.service.GetExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.ConfirmExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.StartExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.UpdateExtractionService;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionConfirmation;
import io.github.guillermodubon.invoward.extraction.domain.BoundingBox;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedDocument;
import io.github.guillermodubon.invoward.extraction.domain.ExtractedLineItem;
import io.github.guillermodubon.invoward.extraction.domain.ExtractionSource;
import io.github.guillermodubon.invoward.identity.application.model.AuthenticatedIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ExtractionControllerTest {

    private static final UUID ANALYSIS_ID = UUID.randomUUID();
    private static final UUID GUEST_ID = UUID.randomUUID();
    private static final AnalysisOwner OWNER = new GuestSessionOwner(
            GUEST_ID, Instant.parse("2026-10-05T00:00:00Z"));
    private static final UUID REFERENCE_DOCUMENT_ID = UUID.randomUUID();
    private static final UUID INVOICE_DOCUMENT_ID = UUID.randomUUID();
    private static final UUID REFERENCE_EXTRACTION_ID = UUID.randomUUID();
    private static final UUID INVOICE_EXTRACTION_ID = UUID.randomUUID();
    private static final UUID REFERENCE_LINE_ID = UUID.randomUUID();
    private static final UUID INVOICE_LINE_ID = UUID.randomUUID();
    private static final String REQUEST_BODY = """
            {"referenceType":"PURCHASE_ORDER","invoiceType":"INVOICE"}
            """;
    private static final String CONFIRM_REQUEST_BODY = """
            {"referenceDocumentId":"%s","referenceExpectedVersion":3,
             "invoiceDocumentId":"%s","invoiceExpectedVersion":4}
            """.formatted(REFERENCE_DOCUMENT_ID, INVOICE_DOCUMENT_ID);

    private final StartExtractionService startService = mock(StartExtractionService.class);
    private final GetExtractionService getService = mock(GetExtractionService.class);
    private final UpdateExtractionService updateService = mock(UpdateExtractionService.class);
    private final ConfirmExtractionService confirmService = mock(ConfirmExtractionService.class);
    private final AnalysisRequestOwnerResolver ownerResolver = mock(AnalysisRequestOwnerResolver.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = standaloneSetup(new ExtractionController(
                        startService, getService, updateService, confirmService, ownerResolver))
                .setControllerAdvice(new ExtractionExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setValidator(validator)
                .build();
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class)))
                .thenReturn(Optional.of(OWNER));
    }

    @AfterEach
    void tearDown() {
        validator.close();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void getReturnsFullSafeReviewWithoutCsrfAndRecordsGuestActivityAfterSuccess() throws Exception {
        when(getService.get(ANALYSIS_ID, OWNER)).thenReturn(review());

        String body = mockMvc.perform(get("/api/analyses/{analysisId}/extraction", ANALYSIS_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reference.status").value("DRAFT"))
                .andExpect(jsonPath("$.reference.version").value(0))
                .andExpect(jsonPath("$.reference.lines[0].id").value(REFERENCE_LINE_ID.toString()))
                .andExpect(jsonPath("$.reference.lines[0].sourceText").value("Hardware part"))
                .andExpect(jsonPath("$.reference.lines[0].boundingBox.xMin").value(0.1))
                .andExpect(jsonPath("$.invoice.status").value("DRAFT"))
                .andExpect(jsonPath("$.invoice.lines[0].id").value(INVOICE_LINE_ID.toString()))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertEquals(2, response.size());
        assertEquals(15, response.get("reference").size());
        assertEquals(13, response.get("reference").get("lines").get(0).size());
        assertFalse(body.contains("modelId"));
        assertFalse(body.contains("extractorVersion"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains("cache"));
        assertFalse(body.contains(GUEST_ID.toString()));
        verify(getService).get(ANALYSIS_ID, OWNER);
        verify(ownerResolver).recordSuccessfulActivity(OWNER);
    }

    @Test
    void getWithoutResolvedOwnerReturnsAnalysisNotFoundBeforeReadingExtraction() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analyses/{analysisId}/extraction", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(getService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void getMapsMissingReviewToStableNotFoundAndDoesNotTouchGuestSession() throws Exception {
        when(getService.get(ANALYSIS_ID, OWNER)).thenThrow(new ExtractionNotFoundException());

        mockMvc.perform(get("/api/analyses/{analysisId}/extraction", ANALYSIS_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"EXTRACTION_NOT_FOUND","message":"Extraction was not found."}
                        """));

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void returnsFullSafeDraftReviewAndRecordsGuestActivityOnlyAfterSuccess() throws Exception {
        ExtractionReview review = review();
        when(startService.start(eq(ANALYSIS_ID), eq(OWNER), any())).thenReturn(review);

        String body = mockMvc.perform(post("/api/analyses/{analysisId}/extract", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reference.status").value("DRAFT"))
                .andExpect(jsonPath("$.reference.role").value("REFERENCE"))
                .andExpect(jsonPath("$.reference.confirmedType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.reference.lines[0].id").value(REFERENCE_LINE_ID.toString()))
                .andExpect(jsonPath("$.reference.lines[0].position").value(0))
                .andExpect(jsonPath("$.reference.lines[0].boundingBox.xMin").value(0.1))
                .andExpect(jsonPath("$.invoice.status").value("DRAFT"))
                .andExpect(jsonPath("$.invoice.role").value("INVOICE"))
                .andExpect(jsonPath("$.invoice.lines[0].id").value(INVOICE_LINE_ID.toString()))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = objectMapper.readTree(body);
        assertEquals(2, response.size());
        assertFalse(body.contains("modelId"));
        assertFalse(body.contains("extractorVersion"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains(GUEST_ID.toString()));
        verify(startService).start(eq(ANALYSIS_ID), eq(OWNER), any());
        verify(ownerResolver).recordSuccessfulActivity(OWNER);
    }

    @Test
    void missingExistingOwnerReturnsNotFoundWithoutCreatingGuestOrCallingExtraction() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{analysisId}/extract", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(startService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void validatesRequiredTypesAndMalformedRequestsSafely() throws Exception {
        mockMvc.perform(post("/api/analyses/{analysisId}/extract", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"referenceType\":\"PURCHASE_ORDER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"VALIDATION_FAILED","message":"The request contains invalid data."}
                        """));

        mockMvc.perform(post("/api/analyses/{analysisId}/extract", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        mockMvc.perform(post("/api/analyses/not-a-uuid/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(startService);
    }

    @Test
    void mapsProviderStorageAndTypeFailuresToSafeErrors() throws Exception {
        when(startService.start(eq(ANALYSIS_ID), eq(OWNER), any()))
                .thenThrow(new DocumentIntelligenceException(DocumentIntelligenceException.Failure.RATE_LIMITED))
                .thenThrow(new DocumentStorageException(DocumentStorageException.Failure.UNAVAILABLE))
                .thenThrow(new DocumentTypesNotDetectedException())
                .thenThrow(new ConfirmedDocumentTypeInvalidException());

        assertSafeError(503, "AI_PROVIDER_UNAVAILABLE",
                "Document intelligence is temporarily unavailable.");
        assertSafeError(503, "DOCUMENT_STORAGE_UNAVAILABLE", "Document storage is unavailable.");
        assertSafeError(409, "DOCUMENT_TYPES_NOT_DETECTED", "Document types must be detected before extraction.");
        assertSafeError(400, "VALIDATION_FAILED", "The request contains invalid data.");

        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void putReturnsRefreshedReviewAndMapsOnlyEditableFields() throws Exception {
        when(updateService.update(eq(ANALYSIS_ID), eq(OWNER), any(ExtractionReviewUpdate.class)))
                .thenReturn(review());

        String body = mockMvc.perform(put("/api/analyses/{analysisId}/extraction", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequestBody()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reference.version").value(0))
                .andExpect(jsonPath("$.reference.confirmedType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.reference.lines[0].position").value(0))
                .andExpect(jsonPath("$.reference.lines[0].sourceText").value("Hardware part"))
                .andExpect(jsonPath("$.invoice.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();

        ExtractionReviewUpdate expected = expectedUpdate();
        verify(updateService).update(eq(ANALYSIS_ID), eq(OWNER), argThat(actual -> actual.equals(expected)));
        assertFalse(body.contains("modelId"));
        assertFalse(body.contains("storageKey"));
        assertFalse(body.contains("sha256"));
        assertFalse(body.contains(GUEST_ID.toString()));
        verify(ownerResolver).recordSuccessfulActivity(OWNER);
    }

    @Test
    void putWithoutResolvedOwnerReturnsAnalysisNotFoundBeforeUpdating() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/analyses/{analysisId}/extraction", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequestBody()))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(updateService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void putMapsStaleLockedAndInvalidReviewFailuresToSafeResponses() throws Exception {
        when(updateService.update(eq(ANALYSIS_ID), eq(OWNER), any(ExtractionReviewUpdate.class)))
                .thenThrow(new ExtractionConflictException())
                .thenThrow(new ExtractionLockedException())
                .thenThrow(new InvalidExtractionReviewException());

        assertPutError(409, "EXTRACTION_CONFLICT",
                "The extraction could not be started in the current state.");
        assertPutError(409, "EXTRACTION_LOCKED", "The extraction can no longer be edited.");
        assertPutError(400, "VALIDATION_FAILED", "The request contains invalid data.");
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void putValidatesRequiredVersionsAndLineFieldsAtTheHttpBoundary() throws Exception {
        String invalid = """
                {"documents":[
                  {"documentId":"%s","confirmedType":"PURCHASE_ORDER","lines":[]},
                  {"documentId":"%s","expectedVersion":0,"confirmedType":"INVOICE","lines":[
                    {"description":" ","quantity":0,"unitPrice":-1,"lineTotal":-1}
                  ]}
                ]}
                """.formatted(REFERENCE_DOCUMENT_ID, INVOICE_DOCUMENT_ID);

        mockMvc.perform(put("/api/analyses/{analysisId}/extraction", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"VALIDATION_FAILED","message":"The request contains invalid data."}
                        """));

        verifyNoInteractions(updateService);
    }

    @Test
    void confirmReturnsNoContentAndRecordsActivityOnlyAfterSuccess() throws Exception {
        mockMvc.perform(post("/api/analyses/{analysisId}/extraction/confirm", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRM_REQUEST_BODY))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));

        verify(confirmService).confirm(ANALYSIS_ID, OWNER,
                new ExtractionConfirmation(REFERENCE_DOCUMENT_ID, 3, INVOICE_DOCUMENT_ID, 4));
        verify(ownerResolver).recordSuccessfulActivity(OWNER);
    }

    @Test
    void confirmWithoutResolvedOwnerReturnsNotFoundBeforeCallingService() throws Exception {
        when(ownerResolver.resolveForRead(isNull(), any(HttpServletRequest.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/analyses/{analysisId}/extraction/confirm", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRM_REQUEST_BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"code":"ANALYSIS_NOT_FOUND","message":"Analysis was not found."}
                        """));

        verifyNoInteractions(confirmService);
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    @Test
    void confirmValidatesBothDocumentIdsAndVersionsAtTheHttpBoundary() throws Exception {
        mockMvc.perform(post("/api/analyses/{analysisId}/extraction/confirm", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"referenceDocumentId":"%s","referenceExpectedVersion":-1,
                                 "invoiceDocumentId":"%s"}
                                """.formatted(REFERENCE_DOCUMENT_ID, INVOICE_DOCUMENT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"VALIDATION_FAILED","message":"The request contains invalid data."}
                        """));

        mockMvc.perform(post("/api/analyses/{analysisId}/extraction/confirm", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"referenceDocumentId":"%s","referenceExpectedVersion":0,
                                 "invoiceDocumentId":"%s","invoiceExpectedVersion":0}
                                """.formatted(REFERENCE_DOCUMENT_ID, REFERENCE_DOCUMENT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"code":"INVALID_REQUEST","message":"The request is invalid."}
                        """));

        verifyNoInteractions(confirmService);
    }

    @Test
    void confirmMapsAlreadyConfirmedAndVersionConflictsToSafeResponses() throws Exception {
        doThrow(new ExtractionAlreadyConfirmedException())
                .doThrow(new ExtractionConflictException())
                .when(confirmService).confirm(eq(ANALYSIS_ID), eq(OWNER), any(ExtractionConfirmation.class));

        assertConfirmError(409, "EXTRACTION_ALREADY_CONFIRMED",
                "The extraction has already been confirmed.");
        assertConfirmError(409, "EXTRACTION_CONFLICT",
                "The extraction could not be started in the current state.");
        verify(ownerResolver, never()).recordSuccessfulActivity(any(AnalysisOwner.class));
    }

    private void assertPutError(int statusCode, String code, String message) throws Exception {
        String body = mockMvc.perform(put("/api/analyses/{analysisId}/extraction", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequestBody()))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("stackTrace"));
        assertFalse(body.contains("database"));
        assertFalse(body.contains("secret"));
    }

    private void assertConfirmError(int statusCode, String code, String message) throws Exception {
        String body = mockMvc.perform(post("/api/analyses/{analysisId}/extraction/confirm", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONFIRM_REQUEST_BODY))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("stackTrace"));
        assertFalse(body.contains("database"));
        assertFalse(body.contains("secret"));
    }

    private static String updateRequestBody() {
        return """
                {"documents":[
                  {"documentId":"%s","expectedVersion":0,"confirmedType":"PURCHASE_ORDER",
                   "vendorName":"Example Vendor","documentNumber":"PO-42","currency":"USD",
                   "subtotal":10.0000,"total":10.0000,"lines":[
                    {"id":"%s","itemCode":"ITEM-1","description":"Hardware part","quantity":1.0000,
                     "unit":"each","unitPrice":10.0000,"lineTotal":10.0000}
                   ]},
                  {"documentId":"%s","expectedVersion":0,"confirmedType":"INVOICE",
                   "vendorName":"Example Vendor","documentNumber":"INV-42","currency":"USD",
                   "subtotal":10.0000,"total":10.0000,"lines":[
                    {"id":"%s","itemCode":"ITEM-1","description":"Hardware part","quantity":1.0000,
                     "unit":"each","unitPrice":10.0000,"lineTotal":10.0000}
                   ]}
                ]}
                """.formatted(REFERENCE_DOCUMENT_ID, REFERENCE_LINE_ID,
                INVOICE_DOCUMENT_ID, INVOICE_LINE_ID);
    }

    private static ExtractionReviewUpdate expectedUpdate() {
        return new ExtractionReviewUpdate(List.of(
                new ExtractionReviewUpdate.DocumentUpdate(REFERENCE_DOCUMENT_ID, 0,
                        DocumentType.PURCHASE_ORDER, "Example Vendor", "PO-42", null, "USD",
                        new BigDecimal("10.0000"), null, null, new BigDecimal("10.0000"),
                        List.of(new ExtractionReviewUpdate.LineUpdate(REFERENCE_LINE_ID,
                                "ITEM-1", "Hardware part", new BigDecimal("1.0000"), "each",
                                new BigDecimal("10.0000"), null, null, new BigDecimal("10.0000")))),
                new ExtractionReviewUpdate.DocumentUpdate(INVOICE_DOCUMENT_ID, 0,
                        DocumentType.INVOICE, "Example Vendor", "INV-42", null, "USD",
                        new BigDecimal("10.0000"), null, null, new BigDecimal("10.0000"),
                        List.of(new ExtractionReviewUpdate.LineUpdate(INVOICE_LINE_ID,
                                "ITEM-1", "Hardware part", new BigDecimal("1.0000"), "each",
                                new BigDecimal("10.0000"), null, null, new BigDecimal("10.0000"))))));
    }

    private void assertSafeError(int statusCode, String code, String message) throws Exception {
        String body = mockMvc.perform(post("/api/analyses/{analysisId}/extract", ANALYSIS_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("provider stack"));
        assertFalse(body.contains("api-key"));
        assertFalse(body.contains("secret"));
    }

    private static ExtractionReview review() {
        ExtractedLineItem referenceLine = line("Hardware part", new BoundingBox(0.1, 0.2, 0.3, 0.4));
        ExtractedLineItem invoiceLine = line("Hardware part", null);
        return new ExtractionReview(
                new ExtractionReview.ReviewedDocument(REFERENCE_EXTRACTION_ID, REFERENCE_DOCUMENT_ID,
                        DocumentRole.REFERENCE, DocumentType.PURCHASE_ORDER, 0,
                        List.of(REFERENCE_LINE_ID), extraction(referenceLine)),
                new ExtractionReview.ReviewedDocument(INVOICE_EXTRACTION_ID, INVOICE_DOCUMENT_ID,
                        DocumentRole.INVOICE, DocumentType.INVOICE, 0,
                        List.of(INVOICE_LINE_ID), extraction(invoiceLine)));
    }

    private static ExtractedDocument extraction(ExtractedLineItem line) {
        return ExtractedDocument.draft(ExtractionSource.AI, "Example Vendor", "DOC-123",
                LocalDate.parse("2026-10-01"), "USD", new BigDecimal("10.0000"), null,
                null, new BigDecimal("10.0000"), List.of(line));
    }

    private static ExtractedLineItem line(String description, BoundingBox boundingBox) {
        return new ExtractedLineItem(0, "ITEM-1", description, new BigDecimal("1.0000"), "each",
                new BigDecimal("10.0000"), null, null, new BigDecimal("10.0000"), 1,
                "Hardware part", boundingBox);
    }
}
