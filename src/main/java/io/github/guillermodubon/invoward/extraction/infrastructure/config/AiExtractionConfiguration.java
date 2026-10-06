package io.github.guillermodubon.invoward.extraction.infrastructure.config;

import io.github.guillermodubon.invoward.extraction.application.port.ExtractionCacheRepository;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import io.github.guillermodubon.invoward.extraction.application.port.ExtractedDocumentRepository;
import io.github.guillermodubon.invoward.extraction.application.service.ConfirmExtractionTypesTransaction;
import io.github.guillermodubon.invoward.extraction.application.service.DocumentIntelligenceFileMaterializer;
import io.github.guillermodubon.invoward.extraction.application.service.ExtractionCacheService;
import io.github.guillermodubon.invoward.extraction.application.service.GetExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.UpdateExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.PrepareExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.PersistExtractionTransaction;
import io.github.guillermodubon.invoward.extraction.application.service.StartExtractionService;
import io.github.guillermodubon.invoward.extraction.application.service.ProviderOutputValidator;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiExtractionProperties.class)
public class AiExtractionConfiguration {

    @Bean
    ProviderOutputValidator providerOutputValidator(AiExtractionProperties properties) {
        return new ProviderOutputValidator(properties.maxLineItems(), properties.maxSourceTextCodePoints());
    }

    @Bean
    ExtractionCacheService extractionCacheService(
            ExtractionCacheRepository repository,
            ProviderOutputValidator validator,
            AiExtractionProperties properties,
            Clock clock) {
        return new ExtractionCacheService(repository, validator, properties.cacheTtl(), clock);
    }

    @Bean
    PrepareExtractionService prepareExtractionService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            ConfirmExtractionTypesTransaction confirmTypesTransaction,
            ExtractionCacheService cacheService,
            DocumentIntelligenceFileMaterializer fileMaterializer,
            DocumentIntelligence documentIntelligence,
            @Value("${spring.ai.google.genai.chat.model:}") String modelId,
            Clock clock) {
        return new PrepareExtractionService(
                analysisService, documentRepository, extractedDocumentRepository,
                confirmTypesTransaction, cacheService, fileMaterializer,
                documentIntelligence, modelId, clock);
    }

    @Bean
    StartExtractionService startExtractionService(
            PrepareExtractionService preparationService,
            PersistExtractionTransaction persistenceTransaction) {
        return new StartExtractionService(preparationService, persistenceTransaction);
    }

    @Bean
    GetExtractionService getExtractionService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository) {
        return new GetExtractionService(analysisService, documentRepository, extractedDocumentRepository);
    }

    @Bean
    UpdateExtractionService updateExtractionService(
            AnalysisRepository analysisRepository,
            DocumentRepository documentRepository,
            ExtractedDocumentRepository extractedDocumentRepository,
            AiExtractionProperties properties,
            Clock clock) {
        return new UpdateExtractionService(analysisRepository, documentRepository,
                extractedDocumentRepository, clock, properties.maxLineItems());
    }

}
