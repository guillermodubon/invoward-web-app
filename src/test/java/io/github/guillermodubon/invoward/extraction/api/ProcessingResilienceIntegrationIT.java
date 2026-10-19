package io.github.guillermodubon.invoward.extraction.api;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.processing.application.model.ProcessingClaim;
import io.github.guillermodubon.invoward.processing.application.service.ClaimProcessingJobTransaction;
import io.github.guillermodubon.invoward.processing.application.service.RecoverProcessingJobTransaction;
import io.github.guillermodubon.invoward.processing.infrastructure.ProcessingWorker;
import io.github.guillermodubon.invoward.reporting.application.exception.ReportStorageException;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises retry and restart recovery across persisted processing stages. */
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
class ProcessingResilienceIntegrationIT {

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
    @Autowired private ClaimProcessingJobTransaction claimTransaction;
    @Autowired private RecoverProcessingJobTransaction recoveryTransaction;
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
    void retriesTransientExtractionFailureAtSameStageWithoutRepeatingClassification() throws Exception {
        Workflow workflow = startWorkflow("extraction-retry");
        queueExtraction(workflow);
        assertEquals(2, intelligence.totalClassifyCalls());

        intelligence.configureFailure(DocumentIntelligenceException.Failure.UNAVAILABLE);
        processingWorker.process(workflow.analysisId());

        JsonNode failed = readJson(get(workflow, analysisPath(workflow.analysisId()) + "/status"));
        assertEquals("FAILED", failed.get("status").asString());
        assertEquals("FAILED", failed.get("jobStatus").asString());
        assertEquals("EXTRACTING", failed.get("currentStage").asString());
        assertEquals("AI_PROVIDER_UNAVAILABLE", failed.get("failureCode").asString());
        assertTrue(failed.get("retryable").asBoolean());
        assertEquals(1, intelligence.extractCalls());
        assertEquals(0, extractedDocumentCount(workflow.analysisId()));

        HttpResponse<String> retry = postWithoutBody(
                workflow, analysisPath(workflow.analysisId()) + "/retry");
        assertAcceptedStatus(retry, workflow, "EXTRACTING", "QUEUED", "EXTRACTING");
        assertEquals(2, intelligence.totalClassifyCalls(), "retry must not repeat classification");
        assertEquals(1, intelligence.extractCalls(), "the HTTP retry request must only queue work");

        intelligence.clearFailure();
        processingWorker.process(workflow.analysisId());

        assertStatus(workflow, "AWAITING_CONFIRMATION", "WAITING_FOR_USER", "AWAITING_CONFIRMATION");
        assertEquals(2, intelligence.totalClassifyCalls(), "classification output must be reused after retry");
        assertEquals(3, intelligence.extractCalls(), "one failed call plus two successfully extracted documents");
        assertEquals(2, extractedDocumentCount(workflow.analysisId()));
        assertEquals(2, extractedLineItemCount(workflow.analysisId()));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_documents ed "
                        + "JOIN invoward.documents d ON d.id = ed.document_id "
                        + "WHERE d.analysis_id = ? AND ed.status = 'DRAFT'",
                Integer.class, workflow.analysisId()));
    }

    @Test
    void retriesReportStorageFailureWithoutRepeatingCompletedStages() throws Exception {
        Workflow workflow = startAutomaticWorkflowThroughQueuedReport("report-retry");
        assertEquals(2, intelligence.totalClassifyCalls());
        assertEquals(2, intelligence.extractCalls());
        assertEquals(0, ambiguousMatcher.callCount());
        assertEquals(1, count("invoward.line_item_matches", workflow.analysisId()));
        assertEquals(1, count("invoward.reconciliation_lines", workflow.analysisId()));
        assertEquals(0, count("invoward.generated_reports", workflow.analysisId()));

        reportStorage.configureFailure(FakeReportStorage.Operation.STORE, ReportStorageException.Failure.UNAVAILABLE);
        processingWorker.process(workflow.analysisId());

        JsonNode failed = readJson(get(workflow, analysisPath(workflow.analysisId()) + "/status"));
        assertEquals("FAILED", failed.get("status").asString());
        assertEquals("FAILED", failed.get("jobStatus").asString());
        assertEquals("GENERATING_REPORT", failed.get("currentStage").asString());
        assertEquals("REPORT_STORAGE_UNAVAILABLE", failed.get("failureCode").asString());
        assertTrue(failed.get("retryable").asBoolean());
        assertEquals(1, reportStorage.operationCalls(FakeReportStorage.Operation.STORE));
        assertEquals(0, reportStorage.storedObjectCount());
        assertEquals(0, count("invoward.generated_reports", workflow.analysisId()));

        HttpResponse<String> reconciliationResponse = get(
                workflow, analysisPath(workflow.analysisId()) + "/reconciliation");
        assertEquals(200, reconciliationResponse.statusCode(), reconciliationResponse.body());
        JsonNode reconciliation = readJson(reconciliationResponse);
        assertEquals("MATCHED", reconciliation.get("reconciliationStatus").asString());
        assertEquals(1, reconciliation.get("summary").get("lineCount").asInt());
        assertEquals(1, count("invoward.reconciliation_lines", workflow.analysisId()),
                "report failure must preserve the completed reconciliation snapshot");

        HttpResponse<String> retry = postWithoutBody(
                workflow, analysisPath(workflow.analysisId()) + "/retry");
        assertAcceptedStatus(retry, workflow, "GENERATING_REPORT", "QUEUED", "GENERATING_REPORT");
        assertEquals(2, intelligence.totalClassifyCalls());
        assertEquals(2, intelligence.extractCalls());
        assertEquals(0, ambiguousMatcher.callCount());
        assertEquals(1, count("invoward.reconciliation_lines", workflow.analysisId()));

        reportStorage.clearFailure(FakeReportStorage.Operation.STORE);
        processingWorker.process(workflow.analysisId());

        assertStatus(workflow, "COMPLETED", "COMPLETED", "COMPLETED");
        assertEquals(1, count("invoward.generated_reports", workflow.analysisId()));
        assertEquals(1, reportStorage.storedObjectCount());
        assertEquals(2, reportStorage.operationCalls(FakeReportStorage.Operation.STORE));
        assertEquals(2, intelligence.totalClassifyCalls(), "retry must not rerun classification");
        assertEquals(2, intelligence.extractCalls(), "retry must not rerun extraction");
        assertEquals(0, ambiguousMatcher.callCount(), "retry must not rerun matching or call AI");
        assertEquals(1, count("invoward.line_item_matches", workflow.analysisId()));
        assertEquals(1, count("invoward.reconciliation_lines", workflow.analysisId()));
    }

    @Test
    void startupRecoveryResumesPersistedRunningStageWithoutRepeatingEarlierStages() throws Exception {
        Workflow workflow = startWorkflow("restart-recovery");
        queueExtraction(workflow);
        assertEquals(2, intelligence.totalClassifyCalls());
        assertEquals(0, intelligence.extractCalls());

        ProcessingClaim interrupted = claimTransaction.claim(workflow.analysisId()).orElseThrow();
        assertEquals("EXTRACTING", interrupted.stage().name());
        assertEquals(1, interrupted.attemptNumber());
        assertStatus(workflow, "EXTRACTING", "RUNNING", "EXTRACTING");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.analysis_jobs "
                        + "WHERE analysis_id = ? AND status = 'RUNNING' AND current_stage = 'EXTRACTING' "
                        + "AND attempt_count = 1",
                Integer.class, workflow.analysisId()));

        recoveryTransaction.recover(workflow.analysisId());

        assertStatus(workflow, "EXTRACTING", "QUEUED", "EXTRACTING");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.analysis_jobs "
                        + "WHERE analysis_id = ? AND status = 'QUEUED' AND current_stage = 'EXTRACTING' "
                        + "AND attempt_count = 1 AND started_at IS NULL",
                Integer.class, workflow.analysisId()));
        assertEquals(2, intelligence.totalClassifyCalls(), "recovery must not repeat completed classification");
        assertEquals(0, intelligence.extractCalls(), "recovery itself must not invoke the provider");

        processingWorker.process(workflow.analysisId());

        assertStatus(workflow, "AWAITING_CONFIRMATION", "WAITING_FOR_USER", "AWAITING_CONFIRMATION");
        assertEquals(2, intelligence.totalClassifyCalls(), "resumption must continue at extraction");
        assertEquals(2, intelligence.extractCalls());
        assertEquals(2, extractedDocumentCount(workflow.analysisId()));
        assertEquals(2, extractedLineItemCount(workflow.analysisId()));
    }

    private Workflow startAutomaticWorkflowThroughQueuedReport(String scenario) throws Exception {
        Workflow workflow = startWorkflow(scenario);
        queueExtraction(workflow);
        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "AWAITING_CONFIRMATION", "WAITING_FOR_USER", "AWAITING_CONFIRMATION");

        JsonNode draft = readJson(get(workflow, analysisPath(workflow.analysisId()) + "/extraction"));
        HttpResponse<String> confirmation = postJson(
                workflow,
                analysisPath(workflow.analysisId()) + "/extraction/confirm",
                confirmation(draft));
        assertAcceptedStatus(confirmation, workflow, "MATCHING", "QUEUED", "MATCHING");

        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "RECONCILING", "QUEUED", "RECONCILING");
        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "GENERATING_REPORT", "QUEUED", "GENERATING_REPORT");
        return workflow;
    }

    private void queueExtraction(Workflow workflow) throws Exception {
        intelligence.setClassification(workflow.referenceDocumentId(), DocumentType.QUOTE);
        intelligence.setClassification(workflow.invoiceDocumentId(), DocumentType.INVOICE);
        intelligence.setExtraction(extractionDraft());

        processingWorker.process(workflow.analysisId());
        assertStatus(workflow, "CLASSIFYING", "WAITING_FOR_USER", "CLASSIFYING");
        HttpResponse<String> extractionQueued = postJson(
                workflow,
                analysisPath(workflow.analysisId()) + "/extract",
                "{\"referenceType\":\"QUOTE\",\"invoiceType\":\"INVOICE\"}");
        assertAcceptedStatus(extractionQueued, workflow, "EXTRACTING", "QUEUED", "EXTRACTING");
    }

    private Workflow startWorkflow(String scenario) throws Exception {
        URI baseUri = applicationUri();
        HttpResponse<String> csrfResponse = httpClient.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/auth/csrf"))
                        .timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrfResponse.statusCode(), csrfResponse.body());
        JsonNode csrf = readJson(csrfResponse);
        String sessionCookie = cookieValue(csrfResponse, SESSION_COOKIE);
        HttpResponse<String> created = sendJson(
                baseUri, "/api/analyses", sessionCookie, null,
                csrf.get("token").asString(), "{}", "POST");
        assertEquals(202, created.statusCode(), created.body());
        UUID analysisId = UUID.fromString(readJson(created).get("id").asString());
        String guestCookie = cookieValue(created, GUEST_COOKIE);
        Workflow workflow = new Workflow(
                baseUri, analysisId, sessionCookie, guestCookie, csrf.get("token").asString(), null, null);

        UUID referenceId = uploadDocument(workflow, "REFERENCE", validPdf(scenario + "-reference"));
        UUID invoiceId = uploadDocument(workflow, "INVOICE", validPdf(scenario + "-invoice"));
        workflow = workflow.withDocuments(referenceId, invoiceId);
        assertStatus(workflow, "CLASSIFYING", "QUEUED", "CLASSIFYING");
        return workflow;
    }

    private UUID uploadDocument(Workflow workflow, String role, byte[] pdf) throws Exception {
        String boundary = "InvoWardBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"role\"\r\n\r\n"
                + role + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"retry-"
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

    private void assertStatus(Workflow workflow, String analysisStatus, String jobStatus, String stage)
            throws Exception {
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

    private HttpResponse<String> sendJson(
            URI baseUri, String path, String sessionCookie, String guestCookie,
            String csrfToken, String body, String method) throws Exception {
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

    private int count(String table, UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE analysis_id = ?", Integer.class, analysisId);
    }

    private int extractedDocumentCount(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_documents ed "
                        + "JOIN invoward.documents d ON d.id = ed.document_id WHERE d.analysis_id = ?",
                Integer.class, analysisId);
    }

    private int extractedLineItemCount(UUID analysisId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invoward.extracted_line_items li "
                        + "JOIN invoward.extracted_documents ed ON ed.id = li.extracted_document_id "
                        + "JOIN invoward.documents d ON d.id = ed.document_id WHERE d.analysis_id = ?",
                Integer.class, analysisId);
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

    private record Workflow(
            URI baseUri,
            UUID analysisId,
            String sessionCookie,
            String guestCookie,
            String csrfToken,
            UUID referenceDocumentId,
            UUID invoiceDocumentId) {

        private Workflow withDocuments(UUID referenceId, UUID invoiceId) {
            return new Workflow(baseUri, analysisId, sessionCookie, guestCookie, csrfToken, referenceId, invoiceId);
        }
    }
}
