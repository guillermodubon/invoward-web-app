package io.github.guillermodubon.invoward.sharing.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SharingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SharingConfiguration.class);

    @Test
    void bindsDefaultBaseUrlAndActiveLinkLimit() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SharingProperties properties = context.getBean(SharingProperties.class);
            assertEquals(URI.create("http://localhost:8080"), properties.publicBaseUrl());
            assertEquals(10, properties.maxActiveLinksPerAnalysis());
        });
    }

    @Test
    void acceptsHttpsAndLocalhostHttpAndNormalizesTrailingSlash() {
        contextRunner.withPropertyValues(
                "invoward.sharing.public-base-url=https://reports.example.com/base/",
                "invoward.sharing.max-active-links-per-analysis=1").run(context -> {
            assertThat(context).hasNotFailed();
            SharingProperties properties = context.getBean(SharingProperties.class);
            assertEquals(URI.create("https://reports.example.com/base"), properties.publicBaseUrl());
            assertEquals(1, properties.maxActiveLinksPerAnalysis());
        });

        contextRunner.withPropertyValues("invoward.sharing.public-base-url=http://127.0.0.1:8080/")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertEquals(URI.create("http://127.0.0.1:8080"),
                            context.getBean(SharingProperties.class).publicBaseUrl());
                });
    }

    @Test
    void rejectsUnsupportedOrMalformedBaseUrls() {
        for (String url : List.of(
                "ftp://reports.example.com",
                "file:///tmp/reports",
                "https://",
                "http://reports.example.com",
                "http://user:secret@localhost:8080",
                "https://reports.example.com?destination=other",
                "https://reports.example.com#fragment",
                "https://reports.example.com/base/../other",
                "https://reports.example.com/%2e%2e/other",
                "https://reports.example.com/base//other",
                "https://reports.example.com/base\\other")) {
            contextRunner.withPropertyValues("invoward.sharing.public-base-url=" + url)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void requiresHttpsForProductionProfiles() {
        for (String profile : List.of("prod", "production")) {
            contextRunner.withPropertyValues(
                    "spring.profiles.active=" + profile,
                    "invoward.sharing.public-base-url=http://localhost:8080")
                    .run(context -> assertThat(context).hasFailed());
        }

        contextRunner.withPropertyValues(
                "spring.profiles.default=production",
                "invoward.sharing.public-base-url=http://localhost:8080")
                .run(context -> assertThat(context).hasFailed());

        contextRunner.withPropertyValues(
                "spring.profiles.active=production",
                "invoward.sharing.public-base-url=https://reports.example.com")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void allowsLocalhostHttpForLocalAndTestProfiles() {
        for (String profile : List.of("local", "test")) {
            contextRunner.withPropertyValues(
                    "spring.profiles.active=" + profile,
                    "invoward.sharing.public-base-url=http://localhost:8080")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Test
    void constrainsActiveLinkLimitToAtMostTen() {
        for (String limit : List.of("0", "11", "-1")) {
            contextRunner.withPropertyValues(
                    "invoward.sharing.max-active-links-per-analysis=" + limit)
                    .run(context -> assertThat(context).hasFailed());
        }

        contextRunner.withPropertyValues("invoward.sharing.max-active-links-per-analysis=10")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
