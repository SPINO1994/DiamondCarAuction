package com.diamond.gateway;

import java.time.Duration;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    @Bean HttpClientCustomizer serviceDns() {
        return client -> client.resolver(resolver -> resolver.cacheMaxTimeToLive(Duration.ofSeconds(5))
            .cacheNegativeTimeToLive(Duration.ofSeconds(1)).queryTimeout(Duration.ofSeconds(3)));
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
