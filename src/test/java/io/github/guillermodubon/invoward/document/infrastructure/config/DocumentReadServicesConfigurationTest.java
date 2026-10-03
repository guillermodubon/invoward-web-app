package io.github.guillermodubon.invoward.document.infrastructure.config;

import io.github.guillermodubon.invoward.analysis.application.service.GetAnalysisService;
import io.github.guillermodubon.invoward.document.application.port.DocumentRepository;
import io.github.guillermodubon.invoward.document.application.service.GetDocumentService;
import io.github.guillermodubon.invoward.document.application.service.ListDocumentsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class DocumentReadServicesConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DocumentConfiguration.class)
            .withBean(GetAnalysisService.class, () -> mock(GetAnalysisService.class))
            .withBean(DocumentRepository.class, () -> mock(DocumentRepository.class))
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void registersBothReadServicesWhenRequiredPortsAreAvailable() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(ListDocumentsService.class).size());
            assertEquals(1, context.getBeansOfType(GetDocumentService.class).size());
        });
    }
}
