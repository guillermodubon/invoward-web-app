package io.github.guillermodubon.invoward.document.infrastructure.config;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisJobRepository;
import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.application.port.DocumentStorage;
import io.github.guillermodubon.invoward.document.application.port.DocumentUploadValidator;
import io.github.guillermodubon.invoward.document.application.service.GetDocumentService;
import io.github.guillermodubon.invoward.document.application.service.ListDocumentsService;
import io.github.guillermodubon.invoward.document.application.service.DocumentStorageKeyGenerator;
import io.github.guillermodubon.invoward.document.application.service.UploadDocumentTransaction;
import io.github.guillermodubon.invoward.document.application.service.UploadDocumentService;
import io.github.guillermodubon.invoward.document.infrastructure.storage.disabled.DisabledDocumentStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DocumentUploadProperties.class, DocumentStorageProperties.class})
public class DocumentConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "invoward.document-storage",
            name = "provider",
            havingValue = "disabled",
            matchIfMissing = true)
    public DocumentStorage disabledDocumentStorage() {
        return new DisabledDocumentStorage();
    }

    @Bean
    public DocumentStorageKeyGenerator documentStorageKeyGenerator() {
        return new DocumentStorageKeyGenerator();
    }

    @Bean
    @ConditionalOnBean({GetAnalysisService.class, DocumentRepository.class})
    public ListDocumentsService listDocumentsService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository) {
        return new ListDocumentsService(analysisService, documentRepository);
    }

    @Bean
    @ConditionalOnBean({GetAnalysisService.class, DocumentRepository.class, DocumentStorage.class})
    public GetDocumentService getDocumentService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository,
            DocumentStorage documentStorage,
            Clock clock,
            DocumentStorageProperties properties) {
        return new GetDocumentService(
                analysisService, documentRepository, documentStorage, clock, properties.downloadUrlTtl());
    }

    @Bean
    @ConditionalOnBean({AnalysisRepository.class, AnalysisJobRepository.class, DocumentRepository.class})
    public UploadDocumentTransaction uploadDocumentTransaction(
            AnalysisRepository analysisRepository,
            AnalysisJobRepository analysisJobRepository,
            DocumentRepository documentRepository,
            Clock clock,
            DocumentUploadProperties properties) {
        return new UploadDocumentTransaction(
                analysisRepository,
                analysisJobRepository,
                documentRepository,
                clock,
                properties.maxCombinedSize().toBytes());
    }

    @Bean
    @ConditionalOnBean({
            GetAnalysisService.class,
            DocumentRepository.class,
            DocumentStorage.class,
            DocumentUploadValidator.class,
            UploadDocumentTransaction.class
    })
    public UploadDocumentService uploadDocumentService(
            GetAnalysisService analysisService,
            DocumentRepository documentRepository,
            DocumentUploadValidator uploadValidator,
            DocumentStorage documentStorage,
            UploadDocumentTransaction transaction,
            DocumentStorageKeyGenerator storageKeyGenerator,
            Clock clock,
            DocumentUploadProperties properties) {
        return new UploadDocumentService(
                analysisService,
                documentRepository,
                uploadValidator,
                documentStorage,
                transaction,
                storageKeyGenerator,
                clock,
                properties.maxCombinedSize().toBytes());
    }
}
