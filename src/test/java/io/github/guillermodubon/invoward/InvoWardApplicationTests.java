package io.github.guillermodubon.invoward;

import io.github.guillermodubon.invoward.notification.application.port.EmailSender;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProvider;
import io.github.guillermodubon.invoward.notification.infrastructure.email.EmailProperties;
import io.github.guillermodubon.invoward.notification.infrastructure.email.disabled.DisabledEmailSender;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository.SpringDataAnalysisJobJpaRepository;
import io.github.guillermodubon.invoward.analysis.infrastructure.persistence.repository.SpringDataAnalysisJpaRepository;
import io.github.guillermodubon.invoward.document.infrastructure.persistence.repository.SpringDataDocumentJpaRepository;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.repository.SpringDataExtractedDocumentJpaRepository;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.repository.SpringDataExtractedLineItemJpaRepository;
import io.github.guillermodubon.invoward.extraction.infrastructure.persistence.repository.SpringDataExtractionCacheJpaRepository;
import io.github.guillermodubon.invoward.extraction.application.port.DocumentIntelligence;
import io.github.guillermodubon.invoward.extraction.infrastructure.ai.disabled.DisabledDocumentIntelligence;
import io.github.guillermodubon.invoward.document.application.service.UploadDocumentService;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataEmailVerificationTokenJpaRepository;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataPasswordResetTokenJpaRepository;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataUserJpaRepository;
import io.github.guillermodubon.invoward.identity.infrastructure.persistence.repository.SpringDataGuestSessionJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.google.genai.api-key=",
        "spring.ai.google.genai.chat.model=",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class InvoWardApplicationTests {

    @MockitoBean
    private SpringDataUserJpaRepository userJpaRepository;

    @MockitoBean
    private SpringDataEmailVerificationTokenJpaRepository emailVerificationTokenJpaRepository;

    @MockitoBean
    private SpringDataPasswordResetTokenJpaRepository passwordResetTokenJpaRepository;

    @MockitoBean
    private SpringDataAnalysisJpaRepository analysisJpaRepository;

    @MockitoBean
    private SpringDataAnalysisJobJpaRepository analysisJobJpaRepository;

    @MockitoBean
    private SpringDataGuestSessionJpaRepository guestSessionJpaRepository;

    @MockitoBean
    private SpringDataDocumentJpaRepository documentJpaRepository;

    @MockitoBean
    private SpringDataExtractedDocumentJpaRepository extractedDocumentJpaRepository;

    @MockitoBean
    private SpringDataExtractedLineItemJpaRepository extractedLineItemJpaRepository;

    @MockitoBean
    private SpringDataExtractionCacheJpaRepository extractionCacheJpaRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    @Test
    void applicationUsesFoundationSafeDefaults() {
        assertEquals("invoward", environment.getProperty("spring.application.name"));
        assertEquals("health,info", environment.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("never", environment.getProperty("management.endpoint.health.show-details"));
        assertEquals("never", environment.getProperty("server.error.include-message"));
        assertEquals("never", environment.getProperty("server.error.include-binding-errors"));
        assertEquals("never", environment.getProperty("server.error.include-stacktrace"));
        assertEquals("false", environment.getProperty("server.error.include-exception"));
    }

    @Test
    void applicationStartsWithAiDisabledAndNoGeminiCredentials() {
        assertEquals("none", environment.getProperty("spring.ai.model.chat"));
        assertEquals("", environment.getProperty("spring.ai.google.genai.api-key"));
        assertEquals("", environment.getProperty("spring.ai.google.genai.chat.model"));
    }

    @Test
    void actuatorHealthEndpointIsPresent() {
        assertNotNull(applicationContext.getBean(HealthEndpoint.class));
    }

    @Test
    void transactionalEmailIsDisabledByDefault() {
        assertEquals(EmailProvider.DISABLED, applicationContext.getBean(EmailProperties.class).provider());
        assertInstanceOf(DisabledEmailSender.class, applicationContext.getBean(EmailSender.class));
    }

    @Test
    void uploadDocumentServiceIsWiredWithTheDisabledStorageDefault() {
        assertNotNull(applicationContext.getBean(UploadDocumentService.class));
    }

    @Test
    void documentIntelligenceDefaultsToDisabledWithoutGeminiCredentials() {
        assertInstanceOf(DisabledDocumentIntelligence.class,
                applicationContext.getBean(DocumentIntelligence.class));
    }

}
