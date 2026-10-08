package com.haylee.textloop;

import java.time.Clock;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;

@SpringBootApplication
public class TextloopApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = application().run(args);
        if (context.getEnvironment().getProperty("textloop.recovery", Boolean.class, false)) {
            System.exit(SpringApplication.exit(context));
        }
    }

    /** Shared by the entry point and startup tests, including recovery's enforced non-web mode. */
    public static SpringApplication application() {
        SpringApplication application = new SpringApplication(TextloopApplication.class);
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            if (event.getEnvironment().getProperty("textloop.recovery", Boolean.class, false)) {
                event.getEnvironment().getPropertySources().addFirst(new MapPropertySource("recovery-mode",
                        Map.of("spring.main.web-application-type", "none")));
            }
        });
        return application;
    }

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

}
