package io.github.guillermodubon.invoward.extraction.infrastructure.ai.gemini;

import io.github.guillermodubon.invoward.document.domain.DocumentRole;
import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException.Failure;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiGeminiDocumentIntelligenceTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void classificationUsesConfiguredModelPrivateMultimodalResourceAndRestrictedOptions() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{\"detectedType\":\"INVOICE\"}"));
        Path privateFile = inputFile("private-input.pdf");
        SpringAiGeminiDocumentIntelligence adapter = adapter(chatModel, "configured-gemini-model");

        var result = adapter.classify(input(privateFile, "application/pdf"));

        assertEquals(DocumentType.INVOICE, result.detectedType());
        var promptCaptor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        GoogleGenAiChatOptions options = (GoogleGenAiChatOptions) prompt.getOptions();
        assertEquals("configured-gemini-model", options.getModel());
        assertEquals(0.0, options.getTemperature());
        assertEquals(1, options.getCandidateCount());
        assertEquals(false, options.getGoogleSearchRetrieval());
        assertEquals(GoogleGenAiChatOptions.ToolChoice.Mode.NONE, options.getToolChoice().mode());
        assertNotNull(options.getResponseSchema());
        assertEquals("application/pdf", prompt.getUserMessage().getMedia().getFirst().getMimeType().toString());
        assertEquals("synthetic-document-bytes",
                new String(prompt.getUserMessage().getMedia().getFirst().getDataAsByteArray()));
        assertFalse(prompt.getSystemMessage().getText().isBlank());
    }

    @Test
    void extractionMapsStructuredOutputWithoutCalculatingValues() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"vendorName":"Vendor","subtotal":12.50,"total":12.50,
                 "lines":[{"description":"Printed item","quantity":2,"unitPrice":6.25,"lineTotal":12.50}]}
                """));
        var draft = adapter(chatModel, "configured-model").extract(
                input(inputFile("invoice.png"), "image/png"), DocumentType.INVOICE);

        assertEquals("Vendor", draft.vendorName());
        assertEquals(1, draft.lines().size());
        assertEquals("Printed item", draft.lines().getFirst().description());
        assertEquals("12.50", draft.total().toPlainString());
    }

    @Test
    void malformedStructuredResponseIsTranslatedWithoutRawBody() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{not-json-secret}"));

        DocumentIntelligenceException failure = assertThrows(DocumentIntelligenceException.class,
                () -> adapter(chatModel, "configured-model")
                        .classify(input(inputFile("image.jpg"), "image/jpeg")));

        assertEquals(Failure.INVALID_RESPONSE, failure.failure());
        assertFalse(failure.toString().contains("secret"));
        assertFalse(failure.getMessage().contains("secret"));
    }

    @Test
    void providerFailureIsTranslatedWithoutExposingProviderMessage() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("api-key-secret response-body"));

        DocumentIntelligenceException failure = assertThrows(DocumentIntelligenceException.class,
                () -> adapter(chatModel, "configured-model")
                        .classify(input(inputFile("file.pdf"), "application/pdf")));

        assertEquals(Failure.UNKNOWN, failure.failure());
        assertFalse(failure.toString().contains("secret"));
        assertFalse(failure.getMessage().contains("response-body"));
    }

    @Test
    void requiredModelComesFromConfigurationRatherThanAnEmbeddedProviderDefault() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter(mock(ChatModel.class), " "));
    }

    private static SpringAiGeminiDocumentIntelligence adapter(ChatModel model, String modelId) {
        return new SpringAiGeminiDocumentIntelligence(
                model, modelId, new GeminiClassificationPrompt(), new GeminiExtractionPrompt());
    }

    private static DocumentIntelligenceInput input(Path path, String contentType) {
        return new DocumentIntelligenceInput(UUID.randomUUID(), DocumentRole.REFERENCE, contentType, 1, path);
    }

    private Path inputFile(String filename) {
        Path path = temporaryDirectory.resolve(filename);
        try {
            Files.writeString(path, "synthetic-document-bytes");
            return path;
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
