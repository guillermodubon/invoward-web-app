package io.github.guillermodubon.invoward.extraction.infrastructure.ai.gemini;

import io.github.guillermodubon.invoward.document.domain.DocumentType;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException;
import io.github.guillermodubon.invoward.extraction.application.exception.DocumentIntelligenceException.Failure;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentClassification;
import io.github.guillermodubon.invoward.extraction.application.model.DocumentIntelligenceInput;
import io.github.guillermodubon.invoward.extraction.application.model.ExtractionDraft;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions.ToolChoice;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions.ToolChoice.Mode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Spring AI Google GenAI adapter. All provider types and failures stay inside infrastructure. */
@Component
@ConditionalOnProperty(prefix = "spring.ai.model", name = "chat", havingValue = "google-genai")
public final class SpringAiGeminiDocumentIntelligence implements DocumentIntelligence {

    private final ChatModel chatModel;
    private final String modelId;
    private final GeminiClassificationPrompt classificationPrompt;
    private final GeminiExtractionPrompt extractionPrompt;

    @Autowired
    public SpringAiGeminiDocumentIntelligence(
            ChatModel chatModel,
            @org.springframework.beans.factory.annotation.Value("${spring.ai.google.genai.chat.model}") String modelId) {
        this(chatModel, modelId, new GeminiClassificationPrompt(), new GeminiExtractionPrompt());
    }

    SpringAiGeminiDocumentIntelligence(
            ChatModel chatModel,
            String modelId,
            GeminiClassificationPrompt classificationPrompt,
            GeminiExtractionPrompt extractionPrompt) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("GEMINI_MODEL is required when Google GenAI is enabled");
        }
        this.modelId = modelId;
        this.classificationPrompt = Objects.requireNonNull(classificationPrompt);
        this.extractionPrompt = Objects.requireNonNull(extractionPrompt);
    }

    @Override
    public DocumentClassification classify(DocumentIntelligenceInput input) {
        Objects.requireNonNull(input, "input must not be null");
        BeanOutputConverter<GeminiStructuredOutput.Classification> converter =
                GeminiStructuredOutputConverters.classification();
        GeminiStructuredOutput.Classification response = invokeStructured(
                input,
                classificationPrompt.systemPrompt(),
                classificationPrompt.userPrompt(input.role()),
                converter);
        if (response.detectedType() == null) {
            throw new DocumentIntelligenceException(Failure.INVALID_RESPONSE);
        }
        return new DocumentClassification(response.detectedType());
    }

    @Override
    public ExtractionDraft extract(DocumentIntelligenceInput input, DocumentType confirmedType) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(confirmedType, "confirmedType must not be null");
        BeanOutputConverter<GeminiStructuredOutput.Extraction> converter =
                GeminiStructuredOutputConverters.extraction();
        GeminiStructuredOutput.Extraction response = invokeStructured(
                input,
                extractionPrompt.systemPrompt(),
                extractionPrompt.userPrompt(confirmedType),
                converter);
        return GeminiStructuredOutputConverters.toDraft(response);
    }

    private <T> T invokeStructured(
            DocumentIntelligenceInput input,
            String systemText,
            String userText,
            BeanOutputConverter<T> converter) {
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(modelId)
                .temperature(0.0)
                .candidateCount(1)
                .googleSearchRetrieval(false)
                .toolChoice(ToolChoice.builder().mode(Mode.NONE).build())
                .responseMimeType("application/json")
                .responseSchema(converter.getJsonSchema())
                .build();
        UserMessage message = UserMessage.builder()
                .text(userText)
                .media(new Media(MimeTypeUtils.parseMimeType(input.contentType()),
                        new FileSystemResource(input.temporaryFile())))
                .build();
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemText), message), options);

        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (RuntimeException exception) {
            throw translateProviderFailure(exception);
        }

        String text = responseText(response);
        try {
            T converted = converter.convert(text);
            if (converted == null) {
                throw new IllegalArgumentException("Structured provider output was empty");
            }
            return converted;
        } catch (RuntimeException exception) {
            throw new DocumentIntelligenceException(Failure.INVALID_RESPONSE);
        }
    }

    private static String responseText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new DocumentIntelligenceException(Failure.INVALID_RESPONSE);
        }
        return response.getResult().getOutput().getText();
    }

    private static DocumentIntelligenceException translateProviderFailure(RuntimeException exception) {
        for (Throwable cause = exception; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            String type = cause.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (type.contains("ratelimit") || type.contains("resourceexhausted")
                    || type.contains("toomanyrequests")) {
                return new DocumentIntelligenceException(Failure.RATE_LIMITED);
            }
            if (type.contains("unauthenticated") || type.contains("unauthorized")
                    || type.contains("authentication") || type.contains("permissiondenied")) {
                return new DocumentIntelligenceException(Failure.AUTHENTICATION);
            }
            if (type.contains("timeout") || type.contains("ioexception")
                    || type.contains("unavailable") || type.contains("connectexception")
                    || type.contains("socketexception")) {
                return new DocumentIntelligenceException(Failure.UNAVAILABLE);
            }
        }
        return new DocumentIntelligenceException(Failure.UNKNOWN);
    }

    @Override
    public String toString() {
        return "SpringAiGeminiDocumentIntelligence[]";
    }
}
