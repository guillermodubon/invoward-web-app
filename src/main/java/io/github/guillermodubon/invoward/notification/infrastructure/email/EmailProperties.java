package io.github.guillermodubon.invoward.notification.infrastructure.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "invoward.email")
public record EmailProperties(
        @DefaultValue("DISABLED") EmailProvider provider,
        @DefaultValue("InvoWard") String fromName) {
}
