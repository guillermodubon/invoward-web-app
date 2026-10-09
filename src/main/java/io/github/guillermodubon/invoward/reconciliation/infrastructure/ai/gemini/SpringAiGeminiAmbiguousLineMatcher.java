package io.github.guillermodubon.invoward.reconciliation.infrastructure.ai.gemini;

import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousLineMatchingInput;
import io.github.guillermodubon.invoward.reconciliation.application.model.AmbiguousMatchSuggestion;
import io.github.guillermodubon.invoward.reconciliation.application.port.AmbiguousLineMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions.ToolChoice;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions.ToolChoice.Mode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Gemini adapter for optional ambiguous-line suggestions; provider types remain inside infrastructure. */
@Component
@ConditionalOnProperty(prefix = "spring.ai.model", name = "chat", havingValue = "google-genai")
public final class SpringAiGeminiAmbiguousLineMatcher implements AmbiguousLineMatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiGeminiAmbiguousLineMatcher.class);

    private final ChatModel chatModel;
    private final String modelId;
    private final GeminiLineMatchPrompt lineMatchPrompt;

    public SpringAiGeminiAmbiguousLineMatcher(
            ChatModel chatModel,
            @Value("${spring.ai.google.genai.chat.model}") String modelId,
            ObjectMapper objectMapper) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("GEMINI_MODEL is required when Google GenAI is enabled");
        }
        this.modelId = modelId;
        this.lineMatchPrompt = new GeminiLineMatchPrompt(objectMapper);
    }

    @Override
    public Optional<AmbiguousMatchSuggestion> suggest(AmbiguousLineMatchingInput input) {
        Objects.requireNonNull(input, "input must not be null");
        long startedAt = System.nanoTime();

        try {
            BeanOutputConverter<GeminiLineMatchStructuredOutput.CandidateKey> converter =
                    GeminiLineMatchStructuredOutputConverters.candidateKey();
            GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                    .model(modelId)
                    .temperature(0.0)
                    .candidateCount(1)
                    .googleSearchRetrieval(false)
                    .toolChoice(ToolChoice.builder().mode(Mode.NONE).build())
                    .responseMimeType("application/json")
                    .responseSchema(converter.getJsonSchema())
                    .build();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(lineMatchPrompt.systemPrompt()),
                    new UserMessage(lineMatchPrompt.userPrompt(input))), options);

            GeminiLineMatchStructuredOutput.CandidateKey response = readResponse(chatModel.call(prompt), converter);
            if (!isAvailableCandidate(response, input)) {
                return fallback("invalid_response", startedAt);
            }

            LOGGER.info("operation=ambiguous_line_matching provider=gemini result=success durationMs={}",
                    elapsedMillis(startedAt));
            return Optional.of(new AmbiguousMatchSuggestion(response.candidateKey()));
        } catch (RuntimeException exception) {
            return fallback(providerFailureCategory(exception), startedAt);
        }
    }

    private static GeminiLineMatchStructuredOutput.CandidateKey readResponse(
            ChatResponse response,
            BeanOutputConverter<GeminiLineMatchStructuredOutput.CandidateKey> converter) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            return null;
        }
        return converter.convert(response.getResult().getOutput().getText());
    }

    private static boolean isAvailableCandidate(
            GeminiLineMatchStructuredOutput.CandidateKey response,
            AmbiguousLineMatchingInput input) {
        if (response == null || response.candidateKey() == null || response.candidateKey().isBlank()) {
            return false;
        }
        return AmbiguousMatchSuggestion.NO_MATCH.equals(response.candidateKey())
                || input.invoiceCandidates().stream()
                .anyMatch(candidate -> candidate.label().equals(response.candidateKey()));
    }

    private static Optional<AmbiguousMatchSuggestion> fallback(String failureType, long startedAt) {
        LOGGER.warn("operation=ambiguous_line_matching provider=gemini result=fallback failureType={} durationMs={}",
                failureType, elapsedMillis(startedAt));
        return Optional.empty();
    }

    private static String providerFailureCategory(RuntimeException exception) {
        for (Throwable cause = exception; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            String type = cause.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (type.contains("ratelimit") || type.contains("resourceexhausted")
                    || type.contains("toomanyrequests")) {
                return "rate_limited";
            }
            if (type.contains("timeout") || type.contains("ioexception") || type.contains("unavailable")
                    || type.contains("connectexception") || type.contains("socketexception")) {
                return "unavailable";
            }
        }
        return "provider_error";
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    @Override
    public String toString() {
        return "SpringAiGeminiAmbiguousLineMatcher[]";
    }
}
