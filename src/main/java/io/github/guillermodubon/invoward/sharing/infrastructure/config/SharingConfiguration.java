package io.github.guillermodubon.invoward.sharing.infrastructure.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Objects;
import java.util.stream.Stream;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SharingProperties.class)
public class SharingConfiguration {

    @Bean
    SmartInitializingSingleton validateSharingPropertiesForRuntimeProfile(
            SharingProperties properties,
            Environment environment) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        String[] runtimeProfiles = Stream.concat(
                        Stream.of(environment.getActiveProfiles()),
                        Stream.of(environment.getDefaultProfiles()))
                .toArray(String[]::new);
        return () -> properties.validateForProfiles(runtimeProfiles);
    }
}
