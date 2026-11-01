package io.github.guillermodubon.invoward.insights.infrastructure.config;

import io.github.guillermodubon.invoward.insights.application.port.InsightsDashboardQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsRecentAnalysisQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierFinancialQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierListQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierQueryRepository;
import io.github.guillermodubon.invoward.insights.application.port.InsightsSupplierSummaryQueryRepository;
import io.github.guillermodubon.invoward.insights.application.service.GetDashboardSummaryService;
import io.github.guillermodubon.invoward.insights.application.service.GetSupplierSummaryService;
import io.github.guillermodubon.invoward.insights.application.service.InsightsMetricCalculator;
import io.github.guillermodubon.invoward.insights.application.service.InsightsPeriodFactory;
import io.github.guillermodubon.invoward.insights.application.service.ListDashboardRecentService;
import io.github.guillermodubon.invoward.insights.application.service.ListSupplierAnalysesService;
import io.github.guillermodubon.invoward.insights.application.service.ListSuppliersService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires dashboard use cases while keeping the Insights application framework-neutral. */
@Configuration(proxyBeanMethods = false)
public class InsightsServiceConfiguration {

    @Bean
    InsightsPeriodFactory insightsPeriodFactory(Clock clock) {
        return new InsightsPeriodFactory(clock);
    }

    @Bean
    InsightsMetricCalculator insightsMetricCalculator() {
        return new InsightsMetricCalculator();
    }

    @Bean
    GetDashboardSummaryService getDashboardSummaryService(
            InsightsDashboardQueryRepository queryRepository,
            InsightsPeriodFactory periodFactory,
            InsightsMetricCalculator metricCalculator) {
        return new GetDashboardSummaryService(queryRepository, periodFactory, metricCalculator);
    }

    @Bean
    ListDashboardRecentService listDashboardRecentService(
            InsightsRecentAnalysisQueryRepository queryRepository) {
        return new ListDashboardRecentService(queryRepository);
    }

    @Bean
    ListSuppliersService listSuppliersService(
            InsightsSupplierListQueryRepository listQueryRepository,
            InsightsSupplierFinancialQueryRepository financialQueryRepository,
            InsightsMetricCalculator metricCalculator) {
        return new ListSuppliersService(listQueryRepository, financialQueryRepository, metricCalculator);
    }

    @Bean
    GetSupplierSummaryService getSupplierSummaryService(
            InsightsSupplierSummaryQueryRepository summaryQueryRepository,
            InsightsSupplierFinancialQueryRepository financialQueryRepository,
            InsightsMetricCalculator metricCalculator) {
        return new GetSupplierSummaryService(summaryQueryRepository, financialQueryRepository, metricCalculator);
    }

    @Bean
    ListSupplierAnalysesService listSupplierAnalysesService(
            InsightsSupplierQueryRepository queryRepository) {
        return new ListSupplierAnalysesService(queryRepository);
    }
}
