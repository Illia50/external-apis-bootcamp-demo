package com.accenture.externalapis.demo.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "external-service")
public record ExternalServiceProperties(String baseUrl,
                                        @DefaultValue("3s") Duration connectTimeout,
                                        @DefaultValue("5s") Duration readTimeout,
                                        @DefaultValue("2") int maxRetries,
                                        @DefaultValue("300ms") Duration retryBackoff) {
}
