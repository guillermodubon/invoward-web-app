package io.github.guillermodubon.invoward.sharing.infrastructure.config;

import io.github.guillermodubon.invoward.analysis.application.port.AnalysisRepository;
import io.github.guillermodubon.invoward.reconciliation.application.service.GetReconciliationService;
import io.github.guillermodubon.invoward.reporting.application.port.GeneratedReportRepository;
import io.github.guillermodubon.invoward.sharing.application.port.ShareLinkRepository;
import io.github.guillermodubon.invoward.sharing.application.port.ShareAuthorizedAnalysisReader;
import io.github.guillermodubon.invoward.sharing.application.port.ShareTokenGenerator;
import io.github.guillermodubon.invoward.sharing.application.service.CreateShareLinkService;
import io.github.guillermodubon.invoward.sharing.application.service.CreateShareLinkTransaction;
import io.github.guillermodubon.invoward.sharing.application.service.GetPublicSharedReportService;
import io.github.guillermodubon.invoward.sharing.application.service.ListShareLinksService;
import io.github.guillermodubon.invoward.sharing.application.service.PublicSharedReportAssembler;
import io.github.guillermodubon.invoward.sharing.application.service.RevokeShareLinkService;
import io.github.guillermodubon.invoward.sharing.application.service.RevokeShareLinkTransaction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires Sharing application use cases from validated provider-neutral configuration values. */
@Configuration(proxyBeanMethods = false)
public class SharingApplicationConfiguration {

    @Bean
    CreateShareLinkTransaction createShareLinkTransaction(
            AnalysisRepository analysisRepository,
            GetReconciliationService reconciliationService,
            GeneratedReportRepository generatedReportRepository,
            ShareLinkRepository shareLinkRepository,
            ShareTokenGenerator tokenGenerator,
            SharingProperties properties,
            Clock clock) {
        return new CreateShareLinkTransaction(
                analysisRepository,
                reconciliationService,
                generatedReportRepository,
                shareLinkRepository,
                tokenGenerator,
                properties.publicBaseUrl(),
                properties.maxActiveLinksPerAnalysis(),
                clock);
    }

    @Bean
    CreateShareLinkService createShareLinkService(CreateShareLinkTransaction transaction) {
        return new CreateShareLinkService(transaction);
    }

    @Bean
    PublicSharedReportAssembler publicSharedReportAssembler() {
        return new PublicSharedReportAssembler();
    }

    @Bean
    GetPublicSharedReportService getPublicSharedReportService(
            ShareTokenGenerator tokenGenerator,
            ShareLinkRepository shareLinkRepository,
            ShareAuthorizedAnalysisReader analysisReader,
            GeneratedReportRepository generatedReportRepository,
            GetReconciliationService reconciliationService,
            PublicSharedReportAssembler assembler,
            Clock clock) {
        return new GetPublicSharedReportService(tokenGenerator, shareLinkRepository, analysisReader,
                generatedReportRepository, reconciliationService, assembler, clock);
    }

    @Bean
    ListShareLinksService listShareLinksService(AnalysisRepository analysisRepository,
            ShareLinkRepository shareLinkRepository, Clock clock) {
        return new ListShareLinksService(analysisRepository, shareLinkRepository, clock);
    }

    @Bean
    RevokeShareLinkTransaction revokeShareLinkTransaction(
            AnalysisRepository analysisRepository, ShareLinkRepository shareLinkRepository, Clock clock) {
        return new RevokeShareLinkTransaction(analysisRepository, shareLinkRepository, clock);
    }

    @Bean
    RevokeShareLinkService revokeShareLinkService(RevokeShareLinkTransaction transaction) {
        return new RevokeShareLinkService(transaction);
    }
}
