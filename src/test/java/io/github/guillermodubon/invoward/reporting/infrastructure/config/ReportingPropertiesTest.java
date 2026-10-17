package io.github.guillermodubon.invoward.reporting.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ReportingConfiguration.class);

    @Test
    void bindsSafeDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ReportingProperties properties = context.getBean(ReportingProperties.class);
            assertEquals(DataSize.ofMegabytes(20), properties.pdf().maxSize());
            assertEquals(DataSize.ofMegabytes(20), properties.csv().maxSize());
            assertEquals(Duration.ofMinutes(5), properties.downloadUrlTtl());
        });
    }

    @Test
    void bindsValidConfiguredValuesAtApprovedBoundaries() {
        contextRunner.withPropertyValues(
                "invoward.reporting.pdf.max-size=50MB",
                "invoward.reporting.csv.max-size=1B",
                "invoward.reporting.download-url-ttl=30s").run(context -> {
            assertThat(context).hasNotFailed();
            ReportingProperties properties = context.getBean(ReportingProperties.class);
            assertEquals(DataSize.ofMegabytes(50), properties.pdf().maxSize());
            assertEquals(DataSize.ofBytes(1), properties.csv().maxSize());
            assertEquals(Duration.ofSeconds(30), properties.downloadUrlTtl());
        });

        contextRunner.withPropertyValues("invoward.reporting.download-url-ttl=15m")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsOutOfRangeSizesAndDownloadTtl() {
        for (String[] properties : List.<String[]>of(
                new String[]{"invoward.reporting.pdf.max-size=0B"},
                new String[]{"invoward.reporting.pdf.max-size=51MB"},
                new String[]{"invoward.reporting.csv.max-size=0B"},
                new String[]{"invoward.reporting.csv.max-size=51MB"},
                new String[]{"invoward.reporting.download-url-ttl=29s"},
                new String[]{"invoward.reporting.download-url-ttl=15m1s"})) {
            contextRunner.withPropertyValues(properties).run(context -> assertThat(context).hasFailed());
        }
    }
}
