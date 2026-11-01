package io.github.guillermodubon.invoward.insights.infrastructure.config;

import io.github.guillermodubon.invoward.insights.application.port.InsightsDashboardQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsRecentAnalysisQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierQueryRepository;
import io.github.guillermodubon.invoward.insights.application.service.GetDashboardSummaryService;
import io.github.guillermodubon.invoward.insights.application.service.GetSupplierSummaryService;
import io.github.guillermodubon.invoward.insights.application.service.InsightsMetricCalculator;
import io.github.guillermodubon.invoward.insights.application.service.InsightsPeriodFactory;
import io.github.guillermodubon.invoward.insights.application.service.ListDashboardRecentService;
import io.github.guillermodubon.invoward.insights.application.service.ListSupplierAnalysesService;
import io.github.guillermodubon.invoward.insights.application.service.ListSuppliersService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class InsightsServiceConfigurationTest {

    @Test
    void wiresTheSummaryUseCaseAndPureCollaboratorsFromInfrastructure() {
        new ApplicationContextRunner()
                .withUserConfiguration(InsightsServiceConfiguration.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(InsightsDashboardQueryRepository.class,
                        () -> mock(InsightsDashboardQueryRepository.class))
                .withBean(InsightsRecentAnalysisQueryRepository.class,
                        () -> mock(InsightsRecentAnalysisQueryRepository.class))
                .withBean(InsightsSupplierQueryRepository.class,
                        () -> mock(InsightsSupplierQueryRepository.class))
                .run(context -> {
                    assertNotNull(context.getBean(GetDashboardSummaryService.class));
                    assertNotNull(context.getBean(ListDashboardRecentService.class));
                    assertNotNull(context.getBean(ListSuppliersService.class));
                    assertNotNull(context.getBean(GetSupplierSummaryService.class));
                    assertNotNull(context.getBean(ListSupplierAnalysesService.class));
                    assertNotNull(context.getBean(InsightsPeriodFactory.class));
                    assertNotNull(context.getBean(InsightsMetricCalculator.class));
                });
    }
}
