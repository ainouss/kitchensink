package io.github.ainouss.kitchensink.facade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The routing facade. It sits in front of the legacy while both systems run, and is removed afterwards.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(FacadeProperties.class)
public class FacadeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FacadeApplication.class, args);
    }
}
