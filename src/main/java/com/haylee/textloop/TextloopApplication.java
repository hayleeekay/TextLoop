package com.haylee.textloop;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import com.haylee.textloop.reminder.RecoveryFailureException;
import com.haylee.textloop.reminder.RecoverySpringApplication;

@SpringBootApplication
public class TextloopApplication {

    public static void main(String[] args) {
        try {
            ConfigurableApplicationContext context = application().run(args);
            if (context.getEnvironment().getProperty("textloop.recovery", Boolean.class, false)) {
                System.exit(SpringApplication.exit(context));
            }
        } catch (RecoveryFailureException failure) {
            System.exit(failure.getExitCode());
        }
    }

    /** Shared by the entry point and startup tests, including recovery's enforced non-web mode. */
    public static SpringApplication application() {
        return new RecoverySpringApplication(TextloopApplication.class);
    }

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

}
