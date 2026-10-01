package io.github.guillermodubon.invoward.analysis.api;

import io.github.guillermodubon.invoward.identity.application.service.GuestSessionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the web-boundary guest cookie policy and owner resolver. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuestCookieProperties.class)
public class AnalysisGuestCookieConfiguration {

    @Bean
    GuestSessionCookieSupport guestSessionCookieSupport(GuestCookieProperties properties, Clock clock) {
        return new GuestSessionCookieSupport(properties, clock);
    }

    @Bean
    AnalysisRequestOwnerResolver analysisRequestOwnerResolver(
            GuestSessionCookieSupport cookieSupport,
            GuestSessionService guestSessionService) {
        return new AnalysisRequestOwnerResolver(cookieSupport, guestSessionService);
    }
}
