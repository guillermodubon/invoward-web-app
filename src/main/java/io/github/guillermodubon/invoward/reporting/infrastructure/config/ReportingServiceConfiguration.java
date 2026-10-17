package io.github.guillermodubon.invoward.reporting.infrastructure.config;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.extraction.application.service.GetExtractionService;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.export.CsvExportGenerator;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.reporting.application.port.ReportArtifactGenerator;
import io.github.guillermodubon.invoward.reporting.application.port.ReportStorage;
import io.github.guillermodubon.invoward.reporting.application.service.ExportReconciliationCsvService;
import io.github.guillermodubon.invoward.reporting.application.service.GenerateReportService;
import io.github.guillermodubon.invoward.reporting.application.service.GetGeneratedReportService;
import io.github.guillermodubon.invoward.reporting.application.service.PersistGeneratedReportTransaction;
import io.github.guillermodubon.invoward.reporting.application.service.ReportDownloadPolicy;
import io.github.guillermodubon.invoward.reporting.application.service.ReportStorageKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires reporting use cases without coupling application code to infrastructure properties. */
@Configuration(proxyBeanMethods = false)
public class ReportingServiceConfiguration {

    @Bean
    CsvExportGenerator csvExportGenerator(ReportingProperties properties) {
        return new CsvExportGenerator(properties.csv().maxSize().toBytes());
    }

    @Bean
    ReportStorageKeyGenerator reportStorageKeyGenerator() {
        return new ReportStorageKeyGenerator();
    }

    @Bean
    ReportDownloadPolicy reportDownloadPolicy() {
        return new ReportDownloadPolicy();
    }

    @Bean
    GenerateReportService generateReportService(
            GetAnalysisService analysisService,
            GeneratedReportRepository generatedReportRepository,
            GetReconciliationService reconciliationService,
            GetExtractionService extractionService,
            ReportArtifactGenerator artifactGenerator,
            ReportStorage reportStorage,
            PersistGeneratedReportTransaction persistenceTransaction,
            ReportStorageKeyGenerator storageKeyGenerator,
            ReportDownloadPolicy downloadPolicy,
            ReportingProperties properties,
            Clock clock) {
        return new GenerateReportService(
                analysisService, generatedReportRepository, reconciliationService, extractionService,
                artifactGenerator, reportStorage, persistenceTransaction, storageKeyGenerator, downloadPolicy,
                properties.downloadUrlTtl(), clock);
    }

    @Bean
    GetGeneratedReportService getGeneratedReportService(
            GetAnalysisService analysisService,
            GeneratedReportRepository generatedReportRepository,
            ReportStorage reportStorage,
            ReportDownloadPolicy downloadPolicy,
            ReportingProperties properties,
            Clock clock) {
        return new GetGeneratedReportService(
                analysisService, generatedReportRepository, reportStorage, downloadPolicy,
                properties.downloadUrlTtl(), clock);
    }

    @Bean
    ExportReconciliationCsvService exportReconciliationCsvService(
            GetAnalysisService analysisService,
            GetReconciliationService reconciliationService,
            CsvExportGenerator csvExportGenerator) {
        return new ExportReconciliationCsvService(analysisService, reconciliationService, csvExportGenerator);
    }
}
