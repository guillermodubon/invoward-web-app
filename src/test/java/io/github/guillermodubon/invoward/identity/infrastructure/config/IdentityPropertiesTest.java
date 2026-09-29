package io.github.guillermodubon.invoward.identity.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentityPropertiesTest {

    private static final String VALID_URL = "https://app.example/verify-email";

    @Test
    void bindsIdentityPropertiesAndProvidesSharedUtcClock() {
        new ApplicationContextRunner()
                .withUserConfiguration(IdentityConfiguration.class)
                .withPropertyValues(
                        "invoward.identity.registration-verification-token-ttl=36h",
                        "invoward.identity.verification-url=https://app.example/verify-email?source=email")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context).hasSingleBean(IdentityProperties.class);
                    assertEquals(ZoneOffset.UTC, context.getBean(Clock.class).getZone());
                    assertEquals(Duration.ofHours(36), context.getBean(IdentityProperties.class)
                            .registrationVerificationTokenTtl());
                });
    }

    @Test
    void acceptsPositiveTtlUpToSeventyTwoHours() {
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ZERO, VALID_URL));
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofNanos(-1), VALID_URL));
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofHours(72).plusNanos(1), VALID_URL));
        assertThrows(IllegalArgumentException.class,
                () -> properties(null, VALID_URL));

        assertEquals(Duration.ofNanos(1), properties(Duration.ofNanos(1), VALID_URL)
                .registrationVerificationTokenTtl());
        assertEquals(Duration.ofHours(72), properties(Duration.ofHours(72), VALID_URL)
                .registrationVerificationTokenTtl());
    }

    @Test
    void rejectsInvalidVerificationUrls() {
        assertInvalidUrl("");
        assertInvalidUrl("/verify-email");
        assertInvalidUrl("ftp://app.example/verify-email");
        assertInvalidUrl("https:///verify-email");
        assertInvalidUrl("https://user:password@app.example/verify-email");
        assertInvalidUrl("https://app.example/verify-email#fragment");
        assertInvalidUrl("https://app.example/verify-email?token=existing");
        assertInvalidUrl("https://app.example/verify-email?%74oken=existing");
        assertInvalidUrl("https://app.example/%zz");
    }

    @Test
    void acceptsHttpAndHttpsUrlsWithNonTokenQueryParameters() {
        assertEquals("http://localhost:3000/verify-email",
                properties(Duration.ofHours(24), "http://localhost:3000/verify-email").verificationUrl());
        assertEquals("https://app.example/verify-email?source=email",
                properties(Duration.ofHours(24), "https://app.example/verify-email?source=email").verificationUrl());
    }

    @Test
    void safelyAppendsAnEncodedTokenAndPreservesExistingQuery() {
        IdentityProperties properties = properties(Duration.ofHours(24),
                "https://app.example/verify-email?source=registration");

        assertEquals("https://app.example/verify-email?source=registration&token=a%2Bb%2Fc%20%3D",
                properties.verificationUrlWithToken("a+b/c ="));
        assertEquals("https://app.example/verify-email?token=opaque-token",
                properties(Duration.ofHours(24), VALID_URL).verificationUrlWithToken("opaque-token"));
    }

    @Test
    void rejectsEmptyVerificationToken() {
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofHours(24), VALID_URL).verificationUrlWithToken(""));
    }

    @Test
    void stringRepresentationDoesNotExposeVerificationUrl() {
        IdentityProperties properties = properties(Duration.ofHours(24), VALID_URL);

        assertThat(properties.toString()).doesNotContain(VALID_URL);
    }

    private static IdentityProperties properties(Duration ttl, String url) {
        return new IdentityProperties(ttl, url);
    }

    private static void assertInvalidUrl(String url) {
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofHours(24), url));
    }
}
