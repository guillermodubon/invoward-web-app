package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.processing.infrastructure.ProcessingWorker;
import io.github.guillermodubon.invoward.support.ai.FakeAmbiguousLineMatcher;
import io.github.guillermodubon.invoward.support.ai.FakeDocumentIntelligence;
import io.github.guillermodubon.invoward.support.database.PostgresTestContainer;
import io.github.guillermodubon.invoward.support.storage.FakeDocumentStorage;
import io.github.guillermodubon.invoward.support.storage.FakeReportStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises both complete persisted processing workflows over real HTTP and PostgreSQL. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "invoward.email.provider=disabled",
                "spring.ai.model.chat=none",
                "spring.ai.google.genai.chat.model=integration-test-model"
        })
@Import({
        ExtractionGuestOwnershipIntegrationIT.TestProviderConfiguration.class,
        ProcessingFullWorkflowIntegrationIT.MatchingConfiguration.class
})
class ProcessingFullWorkflowIntegrationIT {

    private static final String SESSION_COOKIE = "INVOWARD_SESSION";
    private static final String GUEST_COOKIE = "INVOWARD_GUEST";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired private Environment environment;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProcessingWorker processingWorker;
    @Autowired private FakeDocumentStorage documentStorage;
    @Autowired private FakeDocumentIntelligence intelligence;
    @Autowired private FakeReportStorage reportStorage;
    @Autowired private FakeAmbiguousLineMatcher ambiguousMatcher;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        PostgresTestContainer.configure(registry);
    }

    @AfterEach
    void clearFakes() {
        documentStorage.clear();
        intelligence.reset();
        reportStorage.clear();
        ambiguousMatcher.reset();
    }

    @Test
    void automaticallyProcessesExactMatchThroughPersistedReportCompletion() throws Exception {
        Workflow workflow = startWorkflow("automatic");
        configureDocumentTypes(workflow);
        intelligence.setExtraction(extractionDraft());

        assertStatus(workflow, "CLASSIFYING", "QUEUED", "CLASSIFYING");
        CountDownLatch classificationEntered = new CountDownLatch(1);
        CountDownLatch releaseClassification = new CountDownLatch(1);
        intelligence.blockNextClassification(classificationEntered, releaseClassification);
        processAndObserveRunning(workflow, classificationEntered, releaseClassification,
                "CLASSIFYING", "CLASSIFYING");
        assertStatus(workflow, "CLASSIFYING", "WAITING_FOR_USER", "CLASSIFYING");

        HttpResponse<String> extractionQueued = postJson(
                workflow, analysisPath(workflow.analysisId()) + "/extract",
                "{\"referenceType\":\"QUOTE\",\"invoiceType\":\"INVOICE\"}");
        assertAcceptedStatus(extractionQueued, workflow, "EXTRACTING", "QUEUED", "EXTRACTING");
        CountDownLatch extractionEntered = new CountDownLatch(1);
        CountDownLatch releaseExtraction = new CountDownLatch(1);
        intelligence.blockNextExtraction(extractionEntered, releaseExtraction);
        processAndObserveRunning(workflow, extractionEntered, releaseExtraction,
                "EXTRACTING", "EXTRACTING");
        assertStatus(workflow, "AWAITING_CONFIRMATION", "WAITING_FOR_USER", "AWAITING_CONFIRMATION");

        JsonNode draft = readJson(get(workflow, analysisPath(workflow.analysisId()) + "/extraction"));
        assertEquals("DRAFT", draft.get("reference").get("status").asString());
        assertEquals("DRAFT", draft.get("invoice").get("status").asString());
        HttpResponse<String> extractionConfirmed = postJson(
                workflow, analysisPath(workflow.analysisId()) + "/extraction/confirm", confirmation(draft));
        assertAcceptedStatus(extractionConfirmed, workflow, "MATCHING", "QUEUED", "MATCHING");

        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "RECONCILING", "QUEUED", "RECONCILING");
        assertEquals(1, count("invoward.line_item_matches", workflow.analysisId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.line_item_matches WHERE analysis_id = ? AND status = 'MATCHED'",
                Integer.class, workflow.analysisId()));
        assertEquals(0, count("invoward.reconciliation_lines", workflow.analysisId()),
                "matching only queues reconciliation; it does not run it in the same task");

        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "GENERATING_REPORT", "QUEUED", "GENERATING_REPORT");
        assertEquals(1, count("invoward.reconciliation_lines", workflow.analysisId()));
        assertEquals(0, count("invoward.discrepancies", workflow.analysisId()));

        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "COMPLETED", "COMPLETED", "COMPLETED");
        assertPersistedCompletion(
                workflow, "MATCHED", "SKU", "ITEM-PIPELINE-001", "ITEM-PIPELINE-001",
                "Fastener pack", "Fastener pack");
        assertEquals(2, intelligence.totalClassifyCalls());
        assertEquals(2, intelligence.extractCalls());
        assertEquals(0, ambiguousMatcher.callCount(), "an exact SKU match must not invoke ambiguous AI matching");
        assertEquals(1, reportStorage.storedObjectCount());

        JsonNode reconciliation = readJson(get(workflow, analysisPath(workflow.analysisId()) + "/reconciliation"));
        assertEquals("COMPLETED", reconciliation.get("analysisStatus").asString());
        assertEquals("MATCHED", reconciliation.get("reconciliationStatus").asString());
        assertEquals(1, reconciliation.get("summary").get("lineCount").asInt());
        assertEquals(1, reconciliation.get("summary").get("matchedCount").asInt());
        assertEquals(0, reconciliation.get("summary").get("openDiscrepancyCount").asInt());
        assertEquals(200, get(workflow, analysisPath(workflow.analysisId()) + "/report").statusCode());
        HttpResponse<String> csv = get(workflow, analysisPath(workflow.analysisId()) + "/export.csv");
        assertEquals(200, csv.statusCode(), csv.body());
        assertTrue(csv.body().contains("Fastener pack"));
    }

    @Test
    void manualMatchReviewMustBeConfirmedBeforeReconciliationAndReportCompletion() throws Exception {
        Workflow workflow = startWorkflow("review");
        configureDocumentTypes(workflow);
        intelligence.setExtraction(extractionDraft());

        assertStatus(workflow, "CLASSIFYING", "QUEUED", "CLASSIFYING");
        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "CLASSIFYING", "WAITING_FOR_USER", "CLASSIFYING");
        HttpResponse<String> extractionQueued = postJson(
                workflow, analysisPath(workflow.analysisId()) + "/extract",
                "{\"referenceType\":\"QUOTE\",\"invoiceType\":\"INVOICE\"}");
        assertAcceptedStatus(extractionQueued, workflow, "EXTRACTING", "QUEUED", "EXTRACTING");
        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "AWAITING_CONFIRMATION", "WAITING_FOR_USER", "AWAITING_CONFIRMATION");

        JsonNode draft = readJson(get(workflow, analysisPath(workflow.analysisId()) + "/extraction"));
        HttpResponse<String> corrected = putJson(
                workflow,
                analysisPath(workflow.analysisId()) + "/extraction",
                reviewUpdate(draft));
        assertEquals(200, corrected.statusCode(), corrected.body());
        JsonNode correctedReview = readJson(corrected);
        HttpResponse<String> extractionConfirmed = postJson(
                workflow, analysisPath(workflow.analysisId()) + "/extraction/confirm", confirmation(correctedReview));
        assertAcceptedStatus(extractionConfirmed, workflow, "MATCHING", "QUEUED", "MATCHING");

        ambiguousMatcher.configureCandidate("C1");
        CountDownLatch matchingEntered = new CountDownLatch(1);
        CountDownLatch releaseMatching = new CountDownLatch(1);
        ambiguousMatcher.blockNextSuggestion(matchingEntered, releaseMatching);
        processAndObserveRunning(workflow, matchingEntered, releaseMatching, "MATCHING", "MATCHING");
        assertStatus(workflow, "AWAITING_MATCH_REVIEW", "WAITING_FOR_USER", "AWAITING_MATCH_REVIEW");
        assertEquals(1, count("invoward.line_item_matches", workflow.analysisId()));
        assertEquals(0, count("invoward.reconciliation_lines", workflow.analysisId()),
                "reconciliation must remain gated until the user confirms the complete match set");

        JsonNode matches = readJson(get(workflow, analysisPath(workflow.analysisId()) + "/matches"));
        assertTrue(matches.get("reviewRequired").asBoolean());
        JsonNode suggested = matches.get("matches").get(0);
        assertEquals("NEEDS_REVIEW", suggested.get("status").asString());
        assertEquals("AI", suggested.get("method").asString());
        String matchId = suggested.get("id").asString();

        HttpResponse<String> confirmedSuggestion = patchJson(workflow,
                analysisPath(workflow.analysisId()) + "/matches/" + matchId,
                "{\"expectedVersion\":0,\"action\":\"CONFIRM\"}");
        assertEquals(200, confirmedSuggestion.statusCode(), confirmedSuggestion.body());
        assertEquals("MATCHED", readJson(confirmedSuggestion).get("matches").get(0).get("status").asString());
        HttpResponse<String> matchSetConfirmed = postWithoutBody(
                workflow, analysisPath(workflow.analysisId()) + "/matches/confirm");
        assertAcceptedStatus(matchSetConfirmed, workflow, "RECONCILING", "QUEUED", "RECONCILING");
        assertEquals(0, count("invoward.reconciliation_lines", workflow.analysisId()));

        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "GENERATING_REPORT", "QUEUED", "GENERATING_REPORT");
        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "COMPLETED", "COMPLETED", "COMPLETED");
        assertPersistedCompletion(
                workflow, "MATCHED", "AI", "REFERENCE-SKU", "INVOICE-SKU",
                "blue paper archive storage box", "blue paper archive box");
        assertEquals(1, ambiguousMatcher.callCount());
        assertEquals(2, intelligence.totalClassifyCalls());
        assertEquals(2, intelligence.extractCalls());
        assertEquals(1, reportStorage.storedObjectCount());

        JsonNode reconciliation = readJson(get(workflow, analysisPath(workflow.analysisId()) + "/reconciliation"));
        assertEquals("MATCHED", reconciliation.get("reconciliationStatus").asString());
        assertEquals(1, reconciliation.get("summary").get("matchedCount").asInt());
        assertEquals(0, count("invoward.discrepancies", workflow.analysisId()));
    }

    private Workflow startWorkflow(String scenario) throws Exception {
        URI baseUri = applicationUri();
        HttpResponse<String> csrfResponse = httpClient.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/auth/csrf"))
                        .timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrfResponse.statusCode(), csrfResponse.body());
        JsonNode csrf = readJson(csrfResponse);
        assertEquals("X-CSRF-TOKEN", csrf.get("headerName").asString());
        String sessionCookie = cookieValue(csrfResponse, SESSION_COOKIE);
        HttpResponse<String> created = sendJson(
                baseUri, "/api/analyses", sessionCookie, null, csrf.get("token").asString(), "{}", "POST");
        assertEquals(202, created.statusCode(), created.body());
        UUID analysisId = UUID.fromString(readJson(created).get("id").asString());
        String guestCookie = cookieValue(created, GUEST_COOKIE);
        Workflow workflow = new Workflow(
                baseUri, analysisId, sessionCookie, guestCookie, csrf.get("token").asString());

        UUID referenceDocument = uploadDocument(workflow, "REFERENCE", validPdf(scenario + "-reference"));
        UUID invoiceDocument = uploadDocument(workflow, "INVOICE", validPdf(scenario + "-invoice"));
        workflow = workflow.withDocuments(referenceDocument, invoiceDocument);
        assertStatus(workflow, "CLASSIFYING", "QUEUED", "CLASSIFYING");
        return workflow;
    }

    private void configureDocumentTypes(Workflow workflow) {
        intelligence.setClassification(workflow.referenceDocumentId(), DocumentType.QUOTE);
        intelligence.setClassification(workflow.invoiceDocumentId(), DocumentType.INVOICE);
    }

    private UUID uploadDocument(Workflow workflow, String role, byte[] pdf) throws Exception {
        String boundary = "InvoWardBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"role\"\r\n\r\n"
                + role + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"workflow-"
                + role.toLowerCase() + ".pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        multipart.write(pdf);
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpResponse<String> response = httpClient.send(
                requestBuilder(workflow, analysisPath(workflow.analysisId()) + "/documents")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("X-CSRF-TOKEN", workflow.csrfToken())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(readJson(response).get("id").asString());
    }

    private void processAndObserveRunning(
            Workflow workflow,
            CountDownLatch entered,
            CountDownLatch release,
            String expectedAnalysisStatus,
            String expectedStage) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> task = executor.submit(() -> processingWorker.process(workflow.analysisId()));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the worker did not reach the controlled fake adapter");
            assertStatus(workflow, expectedAnalysisStatus, "RUNNING", expectedStage);
        } finally {
            release.countDown();
            executor.shutdown();
        }
        task.get(15, TimeUnit.SECONDS);
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private void assertAcceptedStatus(
            HttpResponse<String> response,
            Workflow workflow,
            String analysisStatus,
            String jobStatus,
            String stage) throws Exception {
        assertEquals(202, response.statusCode(), response.body());
        assertEquals("/api/analyses/" + workflow.analysisId() + "/status",
                response.headers().firstValue("Location").orElseThrow());
        assertStatusBody(readJson(response), workflow.analysisId(), analysisStatus, jobStatus, stage);
        assertStatus(workflow, analysisStatus, jobStatus, stage);
    }

    private void assertStatus(
            Workflow workflow, String analysisStatus, String jobStatus, String stage) throws Exception {
        HttpResponse<String> response = get(workflow, analysisPath(workflow.analysisId()) + "/status");
        assertEquals(200, response.statusCode(), response.body());
        assertStatusBody(readJson(response), workflow.analysisId(), analysisStatus, jobStatus, stage);
    }

    private void assertStatusBody(
            JsonNode status, UUID analysisId, String analysisStatus, String jobStatus, String stage) {
        assertEquals(analysisId.toString(), status.get("analysisId").asString());
        assertEquals(analysisStatus, status.get("status").asString(), status.toString());
        assertEquals(jobStatus, status.get("jobStatus").asString());
        assertEquals(stage, status.get("currentStage").asString());
    }

    private void assertPersistedCompletion(
            Workflow workflow,
            String reconciliationStatus,
            String matchMethod,
            String referenceItemCode,
            String invoiceItemCode,
            String referenceDescription,
            String invoiceDescription) {
        UUID analysisId = workflow.analysisId();
        assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status::text FROM invoward.analyses WHERE id = ?", String.class, analysisId));
        assertEquals(reconciliationStatus, jdbcTemplate.queryForObject(
                "SELECT reconciliation_status::text FROM invoward.analyses WHERE id = ?",
                String.class, analysisId));
        assertEquals("COMPLETED/COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status::text || '/' || current_stage::text FROM invoward.analysis_jobs WHERE analysis_id = ?",
                String.class, analysisId));
        List<Map<String, Object>> persistedExtractions = jdbcTemplate.queryForList(
                "SELECT d.role, d.confirmed_type, ed.status AS extraction_status, ed.vendor_name, "
                        + "ed.document_number, ed.document_date::text AS document_date, ed.currency, "
                        + "ed.subtotal, ed.discount_total, ed.tax_total, ed.total, li.item_code, "
                        + "li.description, li.quantity, li.unit, li.unit_price, li.discount_amount, "
                        + "li.tax_amount, li.line_total "
                        + "FROM invoward.documents d "
                        + "JOIN invoward.extracted_documents ed ON ed.document_id = d.id "
                        + "JOIN invoward.extracted_line_items li ON li.extracted_document_id = ed.id "
                        + "WHERE d.analysis_id = ? "
                        + "ORDER BY CASE WHEN d.role = 'REFERENCE' THEN 0 ELSE 1 END",
                analysisId);
        assertEquals(2, persistedExtractions.size());
        assertPersistedExtraction(persistedExtractions.get(0), "REFERENCE", "QUOTE",
                referenceItemCode, referenceDescription);
        assertPersistedExtraction(persistedExtractions.get(1), "INVOICE", "INVOICE",
                invoiceItemCode, invoiceDescription);

        assertEquals(1, count("invoward.line_item_matches", analysisId));
        Map<String, Object> persistedMatch = jdbcTemplate.queryForMap(
                "SELECT status, method, confidence, reviewed_at FROM invoward.line_item_matches WHERE analysis_id = ?",
                analysisId);
        assertEquals("MATCHED", persistedMatch.get("status"));
        assertEquals(matchMethod, persistedMatch.get("method"));
        if ("SKU".equals(matchMethod)) {
            assertDecimal(persistedMatch.get("confidence"), "1.0000");
            assertNull(persistedMatch.get("reviewed_at"));
        } else {
            assertTrue(persistedMatch.get("confidence") instanceof BigDecimal);
            assertTrue(((BigDecimal) persistedMatch.get("confidence")).compareTo(new BigDecimal("0.65")) >= 0);
            assertTrue(persistedMatch.get("reviewed_at") != null);
        }

        assertEquals(1, count("invoward.reconciliation_lines", analysisId));
        Map<String, Object> persistedLine = jdbcTemplate.queryForMap(
                "SELECT status, reference_item_code, invoice_item_code, reference_description, invoice_description, "
                        + "reference_quantity, invoice_quantity, reference_unit_price, invoice_unit_price, "
                        + "reference_line_total, invoice_line_total, quantity_difference, unit_price_difference, "
                        + "line_total_difference FROM invoward.reconciliation_lines WHERE analysis_id = ?",
                analysisId);
        assertEquals("MATCHED", persistedLine.get("status"));
        assertEquals(referenceItemCode, persistedLine.get("reference_item_code"));
        assertEquals(invoiceItemCode, persistedLine.get("invoice_item_code"));
        assertEquals(referenceDescription, persistedLine.get("reference_description"));
        assertEquals(invoiceDescription, persistedLine.get("invoice_description"));
        assertDecimal(persistedLine.get("reference_quantity"), "1.0000");
        assertDecimal(persistedLine.get("invoice_quantity"), "1.0000");
        assertDecimal(persistedLine.get("reference_unit_price"), "10.0000");
        assertDecimal(persistedLine.get("invoice_unit_price"), "10.0000");
        assertDecimal(persistedLine.get("reference_line_total"), "10.0000");
        assertDecimal(persistedLine.get("invoice_line_total"), "10.0000");
        assertDecimal(persistedLine.get("quantity_difference"), "0.0000");
        assertDecimal(persistedLine.get("unit_price_difference"), "0.0000");
        assertDecimal(persistedLine.get("line_total_difference"), "0.0000");
        assertEquals(0, count("invoward.discrepancies", analysisId));
        assertEquals(1, count("invoward.generated_reports", analysisId));
        Map<String, Object> persistedReport = jdbcTemplate.queryForMap(
                "SELECT content_type, size_bytes, sha256 FROM invoward.generated_reports WHERE analysis_id = ?",
                analysisId);
        assertEquals("application/pdf", persistedReport.get("content_type"));
        assertTrue(((Number) persistedReport.get("size_bytes")).longValue() > 0);
        assertTrue(persistedReport.get("sha256").toString().matches("[0-9a-f]{64}"));
        assertTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT gr.expires_at = a.expires_at FROM invoward.generated_reports gr "
                        + "JOIN invoward.analyses a ON a.id = gr.analysis_id WHERE gr.analysis_id = ?",
                Boolean.class, analysisId)), "guest report expiry must match its Analysis expiry");
    }

    private void assertPersistedExtraction(
            Map<String, Object> row,
            String role,
            String confirmedType,
            String itemCode,
            String description) {
        assertEquals(role, row.get("role"));
        assertEquals(confirmedType, row.get("confirmed_type"));
        assertEquals("CONFIRMED", row.get("extraction_status"));
        assertEquals("InvoWard Synthetic Supplier", row.get("vendor_name"));
        assertEquals("INV-PIPELINE-001", row.get("document_number"));
        assertEquals("2026-10-01", row.get("document_date"));
        assertEquals("USD", row.get("currency"));
        assertDecimal(row.get("subtotal"), "10.0000");
        assertDecimal(row.get("discount_total"), "0.0000");
        assertDecimal(row.get("tax_total"), "0.0000");
        assertDecimal(row.get("total"), "10.0000");
        assertEquals(itemCode, row.get("item_code"));
        assertEquals(description, row.get("description"));
        assertDecimal(row.get("quantity"), "1.0000");
        assertEquals("each", row.get("unit"));
        assertDecimal(row.get("unit_price"), "10.0000");
        assertDecimal(row.get("discount_amount"), "0.0000");
        assertDecimal(row.get("tax_amount"), "0.0000");
        assertDecimal(row.get("line_total"), "10.0000");
    }

    private static void assertDecimal(Object actual, String expected) {
        assertTrue(actual instanceof BigDecimal, "expected a persisted decimal value");
        assertEquals(0, ((BigDecimal) actual).compareTo(new BigDecimal(expected)));
    }

    private int count(String table, UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE analysis_id = ?", Integer.class, analysisId);
    }

    private HttpResponse<String> get(Workflow workflow, String path) throws Exception {
        return httpClient.send(requestBuilder(workflow, path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(Workflow workflow, String path, String body) throws Exception {
        return sendJson(workflow.baseUri(), path, workflow.sessionCookie(), workflow.guestCookie(),
                workflow.csrfToken(), body, "POST");
    }

    private HttpResponse<String> postWithoutBody(Workflow workflow, String path) throws Exception {
        return httpClient.send(requestBuilder(workflow, path)
                        .header("X-CSRF-TOKEN", workflow.csrfToken())
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(Workflow workflow, String path, String body) throws Exception {
        return sendJson(workflow.baseUri(), path, workflow.sessionCookie(), workflow.guestCookie(),
                workflow.csrfToken(), body, "PUT");
    }

    private HttpResponse<String> patchJson(Workflow workflow, String path, String body) throws Exception {
        return sendJson(workflow.baseUri(), path, workflow.sessionCookie(), workflow.guestCookie(),
                workflow.csrfToken(), body, "PATCH");
    }

    private HttpResponse<String> sendJson(
            URI baseUri,
            String path,
            String sessionCookie,
            String guestCookie,
            String csrfToken,
            String body,
            String method) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Cookie", cookies(sessionCookie, guestCookie))
                .header("X-CSRF-TOKEN", csrfToken);
        return httpClient.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder requestBuilder(Workflow workflow, String path) {
        return HttpRequest.newBuilder(workflow.baseUri().resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Cookie", cookies(workflow.sessionCookie(), workflow.guestCookie()));
    }

    private JsonNode readJson(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private String confirmation(JsonNode review) throws Exception {
        JsonNode reference = review.get("reference");
        JsonNode invoice = review.get("invoice");
        return objectMapper.writeValueAsString(Map.of(
                "referenceDocumentId", reference.get("documentId").asString(),
                "referenceExpectedVersion", reference.get("version").asLong(),
                "invoiceDocumentId", invoice.get("documentId").asString(),
                "invoiceExpectedVersion", invoice.get("version").asLong()));
    }

    private String reviewUpdate(JsonNode review) throws Exception {
        return objectMapper.writeValueAsString(Map.of("documents", List.of(
                updatedDocument(review.get("reference"), "QUOTE", "REFERENCE-SKU",
                        "blue paper archive storage box"),
                updatedDocument(review.get("invoice"), "INVOICE", "INVOICE-SKU",
                        "blue paper archive box"))));
    }

    private Map<String, Object> updatedDocument(
            JsonNode document, String type, String itemCode, String description) {
        JsonNode line = document.get("lines").get(0);
        Map<String, Object> lineUpdate = new LinkedHashMap<>();
        lineUpdate.put("id", line.get("id").asString());
        lineUpdate.put("itemCode", itemCode);
        lineUpdate.put("description", description);
        lineUpdate.put("quantity", line.get("quantity").decimalValue());
        lineUpdate.put("unit", line.get("unit").asString());
        lineUpdate.put("unitPrice", line.get("unitPrice").decimalValue());
        lineUpdate.put("discountAmount", line.get("discountAmount").decimalValue());
        lineUpdate.put("taxAmount", line.get("taxAmount").decimalValue());
        lineUpdate.put("lineTotal", line.get("lineTotal").decimalValue());

        Map<String, Object> documentUpdate = new LinkedHashMap<>();
        documentUpdate.put("documentId", document.get("documentId").asString());
        documentUpdate.put("expectedVersion", document.get("version").asLong());
        documentUpdate.put("confirmedType", type);
        documentUpdate.put("vendorName", document.get("vendorName").asString());
        documentUpdate.put("documentNumber", document.get("documentNumber").asString());
        documentUpdate.put("documentDate", document.get("documentDate").asString());
        documentUpdate.put("currency", document.get("currency").asString());
        documentUpdate.put("subtotal", document.get("subtotal").decimalValue());
        documentUpdate.put("discountTotal", document.get("discountTotal").decimalValue());
        documentUpdate.put("taxTotal", document.get("taxTotal").decimalValue());
        documentUpdate.put("total", document.get("total").decimalValue());
        documentUpdate.put("lines", List.of(lineUpdate));
        return documentUpdate;
    }

    private static ExtractionDraft extractionDraft() {
        BigDecimal ten = new BigDecimal("10.0000");
        BigDecimal zero = BigDecimal.ZERO.setScale(4);
        return new ExtractionDraft(
                "InvoWard Synthetic Supplier", "INV-PIPELINE-001", "2026-10-01", "USD",
                ten, zero, zero, ten,
                List.of(new ExtractionDraft.Line(
                        "ITEM-PIPELINE-001", "Fastener pack", BigDecimal.ONE.setScale(4), "each",
                        ten, zero, zero, ten, 1, "Fastener pack 10.00", null)));
    }

    private static byte[] validPdf(String title) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle(title + "-" + UUID.randomUUID());
            document.save(output);
            return output.toByteArray();
        }
    }

    private URI applicationUri() {
        return URI.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private static String analysisPath(UUID analysisId) {
        return "/api/analyses/" + analysisId;
    }

    private static String cookies(String sessionCookie, String guestCookie) {
        List<String> values = new ArrayList<>();
        values.add(SESSION_COOKIE + "=" + sessionCookie);
        if (guestCookie != null) {
            values.add(GUEST_COOKIE + "=" + guestCookie);
        }
        return String.join("; ", values);
    }

    private static String cookieValue(HttpResponse<?> response, String cookieName) {
        String prefix = cookieName + "=";
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()).split(";", 2)[0])
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a " + cookieName + " cookie."));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MatchingConfiguration {

        @Bean
        @Primary
        FakeAmbiguousLineMatcher fakeAmbiguousLineMatcher() {
            return new FakeAmbiguousLineMatcher();
        }
    }

    private record Workflow(
            URI baseUri,
            UUID analysisId,
            String sessionCookie,
            String guestCookie,
            String csrfToken,
            UUID referenceDocumentId,
            UUID invoiceDocumentId) {

        private Workflow(URI baseUri, UUID analysisId, String sessionCookie, String guestCookie, String csrfToken) {
            this(baseUri, analysisId, sessionCookie, guestCookie, csrfToken, null, null);
        }

        private Workflow withDocuments(UUID referenceId, UUID invoiceId) {
            return new Workflow(baseUri, analysisId, sessionCookie, guestCookie, csrfToken, referenceId, invoiceId);
        }
    }
}
