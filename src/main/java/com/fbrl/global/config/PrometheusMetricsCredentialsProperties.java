package com.fbrl.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "prometheus.metrics")
public record PrometheusMetricsCredentialsProperties(String username, String password) {}
