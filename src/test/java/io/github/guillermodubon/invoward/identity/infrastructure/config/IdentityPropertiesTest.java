package io.github.guillermodubon.invoward.identity.infrastructure.config;

import io.github.guillermodubon.invoward.identity.application.port.EmailChangeConfirmationLinkBuilder;
import io.github.guillermodubon.invoward.identity.application.port.PasswordResetLinkBuilder;
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
                        "invoward.identity.verification-url=https://app.example/verify-email?source=email",
                        "invoward.identity.verification-resend-cooldown=45s",
                        "invoward.identity.password-reset-token-ttl=45m",
                        "invoward.identity.password-reset-request-cooldown=90s",
                        "invoward.identity.password-reset-url=https://app.example/reset?flow=account",
                        "invoward.identity.email-change-token-ttl=48h",
                        "invoward.identity.email-change-request-cooldown=2m",
                        "invoward.identity.email-change-confirmation-url=https://app.example/confirm?flow=email-change")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context).hasSingleBean(IdentityProperties.class);
                    assertEquals(ZoneOffset.UTC, context.getBean(Clock.class).getZone());
                    assertEquals(Duration.ofHours(36), context.getBean(IdentityProperties.class)
                            .registrationVerificationTokenTtl());
                    assertEquals(Duration.ofSeconds(45), context.getBean(IdentityProperties.class)
                            .verificationResendCooldown());
                    assertEquals(Duration.ofMinutes(45), context.getBean(IdentityProperties.class)
                            .passwordResetTokenTtl());
                    assertEquals(Duration.ofSeconds(90), context.getBean(IdentityProperties.class)
                            .passwordResetRequestCooldown());
                    assertEquals(Duration.ofHours(48), context.getBean(IdentityProperties.class)
                            .emailChangeTokenTtl());
                    assertEquals(Duration.ofMinutes(2), context.getBean(IdentityProperties.class)
                            .emailChangeRequestCooldown());
                });
    }

    @Test
    void acceptsResetTtlBoundsAndRejectsOutOfRangeValues() {
        assertEquals(Duration.ofMinutes(10), propertiesWithResetTtl(Duration.ofMinutes(10))
                .passwordResetTokenTtl());
        assertEquals(Duration.ofMinutes(60), propertiesWithResetTtl(Duration.ofMinutes(60))
                .passwordResetTokenTtl());

        assertThrows(IllegalArgumentException.class,
                () -> propertiesWithResetTtl(Duration.ofMinutes(10).minusNanos(1)));
        assertThrows(IllegalArgumentException.class,
                () -> propertiesWithResetTtl(Duration.ofMinutes(60).plusNanos(1)));
        assertThrows(IllegalArgumentException.class,
                () -> propertiesWithResetTtl(null));
    }

    @Test
    void validatesEachCooldownWithinThirtySecondsAndTenMinutes() {
        for (Duration invalid : new Duration[] {
                Duration.ofSeconds(29), Duration.ofMinutes(10).plusNanos(1), null
        }) {
            assertThrows(IllegalArgumentException.class, () -> propertiesWithCooldowns(invalid,
                    Duration.ofSeconds(60), Duration.ofSeconds(60)));
            assertThrows(IllegalArgumentException.class, () -> propertiesWithCooldowns(Duration.ofSeconds(60),
                    invalid, Duration.ofSeconds(60)));
            assertThrows(IllegalArgumentException.class, () -> propertiesWithCooldowns(Duration.ofSeconds(60),
                    Duration.ofSeconds(60), invalid));
        }

        assertEquals(Duration.ofSeconds(30), propertiesWithCooldowns(
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30))
                .verificationResendCooldown());
        assertEquals(Duration.ofMinutes(10), propertiesWithCooldowns(
                Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofMinutes(10))
                .emailChangeRequestCooldown());
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
    void rejectsUnsafeResetAndEmailChangeUrls() {
        assertInvalidResetUrl("/reset-password");
        assertInvalidResetUrl("ftp://app.example/reset-password");
        assertInvalidResetUrl("https://user:password@app.example/reset-password");
        assertInvalidResetUrl("https://app.example/reset-password#fragment");
        assertInvalidResetUrl("https://app.example/reset-password?token=existing");
        assertInvalidResetUrl("https://app.example/reset-password?%74oken=existing");

        assertInvalidEmailChangeUrl("/confirm-email");
        assertInvalidEmailChangeUrl("ftp://app.example/confirm-email");
        assertInvalidEmailChangeUrl("https://user:password@app.example/confirm-email");
        assertInvalidEmailChangeUrl("https://app.example/confirm-email#fragment");
        assertInvalidEmailChangeUrl("https://app.example/confirm-email?token=existing");
        assertInvalidEmailChangeUrl("https://app.example/confirm-email?%74oken=existing");
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

        IdentityProperties lifecycleProperties = propertiesWithUrls(
                VALID_URL,
                "https://app.example/reset-password?source=forgot-password",
                "https://app.example/verify-email?flow=email-change");
        assertEquals("https://app.example/reset-password?source=forgot-password&token=a%2Bb%2Fc%20%3D",
                lifecycleProperties.passwordResetUrlWithToken("a+b/c ="));
        assertEquals("https://app.example/verify-email?flow=email-change&token=opaque-token",
                lifecycleProperties.emailChangeConfirmationUrlWithToken("opaque-token"));
    }

    @Test
    void rejectsEmptyVerificationToken() {
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofHours(24), VALID_URL).verificationUrlWithToken(""));
        assertThrows(IllegalArgumentException.class,
                () -> propertiesWithUrls(VALID_URL, "https://app.example/reset-password", VALID_URL)
                        .passwordResetUrlWithToken(""));
        assertThrows(IllegalArgumentException.class,
                () -> propertiesWithUrls(VALID_URL, VALID_URL, VALID_URL)
                        .emailChangeConfirmationUrlWithToken(""));
    }

    @Test
    void exposesSafeLifecycleLinkBuilderPorts() {
        new ApplicationContextRunner()
                .withUserConfiguration(IdentityConfiguration.class, IdentityLifecycleLinkConfiguration.class)
                .withPropertyValues(
                        "invoward.identity.registration-verification-token-ttl=24h",
                        "invoward.identity.verification-url=https://app.example/verify-email",
                        "invoward.identity.verification-resend-cooldown=60s",
                        "invoward.identity.password-reset-token-ttl=30m",
                        "invoward.identity.password-reset-request-cooldown=60s",
                        "invoward.identity.password-reset-url=https://app.example/reset-password?flow=reset",
                        "invoward.identity.email-change-token-ttl=24h",
                        "invoward.identity.email-change-request-cooldown=60s",
                        "invoward.identity.email-change-confirmation-url=https://app.example/verify-email?flow=email-change")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PasswordResetLinkBuilder.class);
                    assertThat(context).hasSingleBean(EmailChangeConfirmationLinkBuilder.class);
                    assertEquals(
                            "https://app.example/reset-password?flow=reset&token=opaque-token",
                            context.getBean(PasswordResetLinkBuilder.class).build("opaque-token"));
                    assertEquals(
                            "https://app.example/verify-email?flow=email-change&token=opaque-token",
                            context.getBean(EmailChangeConfirmationLinkBuilder.class).build("opaque-token"));
                });
    }

    @Test
    void stringRepresentationDoesNotExposeVerificationUrl() {
        IdentityProperties properties = propertiesWithUrls(
                VALID_URL,
                "https://app.example/reset-password?private=value",
                "https://app.example/verify-email?private=value");

        assertThat(properties.toString())
                .doesNotContain(VALID_URL)
                .doesNotContain("https://app.example/reset-password")
                .doesNotContain("https://app.example/verify-email");
    }

    private static IdentityProperties properties(Duration ttl, String url) {
        return new IdentityProperties(
                ttl,
                url,
                Duration.ofSeconds(60),
                Duration.ofMinutes(30),
                Duration.ofSeconds(60),
                "https://app.example/reset-password",
                Duration.ofHours(24),
                Duration.ofSeconds(60),
                "https://app.example/verify-email?flow=email-change");
    }

    private static IdentityProperties propertiesWithResetTtl(Duration resetTtl) {
        return new IdentityProperties(
                Duration.ofHours(24), VALID_URL, Duration.ofSeconds(60), resetTtl, Duration.ofSeconds(60),
                "https://app.example/reset-password", Duration.ofHours(24), Duration.ofSeconds(60),
                "https://app.example/verify-email?flow=email-change");
    }

    private static IdentityProperties propertiesWithCooldowns(
            Duration verificationCooldown,
            Duration resetCooldown,
            Duration emailChangeCooldown) {
        return new IdentityProperties(
                Duration.ofHours(24), VALID_URL, verificationCooldown, Duration.ofMinutes(30), resetCooldown,
                "https://app.example/reset-password", Duration.ofHours(24), emailChangeCooldown,
                "https://app.example/verify-email?flow=email-change");
    }

    private static IdentityProperties propertiesWithUrls(
            String verificationUrl,
            String resetUrl,
            String emailChangeUrl) {
        return new IdentityProperties(
                Duration.ofHours(24), verificationUrl, Duration.ofSeconds(60), Duration.ofMinutes(30),
                Duration.ofSeconds(60), resetUrl, Duration.ofHours(24), Duration.ofSeconds(60), emailChangeUrl);
    }

    private static void assertInvalidResetUrl(String url) {
        assertThrows(IllegalArgumentException.class,
                () -> propertiesWithUrls(VALID_URL, url, "https://app.example/verify-email"));
    }

    private static void assertInvalidEmailChangeUrl(String url) {
        assertThrows(IllegalArgumentException.class,
                () -> propertiesWithUrls(VALID_URL, "https://app.example/reset-password", url));
    }

    private static void assertInvalidUrl(String url) {
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofHours(24), url));
    }
}
