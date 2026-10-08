package com.haylee.textloop.reminder;

import java.util.Map;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.env.EnvironmentPostProcessorApplicationListener;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;

/** Recovery has its own safe failure boundary, including failures before runners execute. */
public class RecoveryModeListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    private final boolean beforeConfigLoading;
    private boolean recoveryMode;

    public RecoveryModeListener() {
        this(false);
    }

    RecoveryModeListener(boolean beforeConfigLoading) {
        this.beforeConfigLoading = beforeConfigLoading;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        recoveryMode = event.getEnvironment().getProperty("textloop.recovery", Boolean.class, false);
        if (recoveryMode && beforeConfigLoading) {
            // ConfigData can fail before Boot applies logging properties or creates a context.
            LoggingSystem logging = LoggingSystem.get(event.getSpringApplication().getClassLoader());
            logging.setLogLevel("org.springframework", LogLevel.OFF);
            logging.setLogLevel(RecoveryFailureReporter.class.getName(), LogLevel.ERROR);
        } else if (recoveryMode) {
            event.getEnvironment().getPropertySources().addFirst(new MapPropertySource("recovery-mode", Map.of(
                    "spring.main.web-application-type", "none",
                    "spring.main.log-startup-info", "false",
                    // These components can log original exception text before Boot's failure reporter runs.
                    "logging.level.org.springframework", "OFF",
                    "logging.level.org.hibernate", "OFF",
                    "logging.level.com.zaxxer.hikari", "OFF",
                    "logging.level.com.haylee.textloop.reminder.DeliveryRecoveryCommand", "INFO",
                    "logging.level.com.haylee.textloop.reminder.RecoveryFailureReporter", "ERROR")));
        }
    }

    @Override
    public int getOrder() {
        return beforeConfigLoading ? EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER - 1
                : LoggingApplicationListener.DEFAULT_ORDER - 1;
    }

    boolean isRecoveryMode() {
        return recoveryMode;
    }

    void reset() {
        recoveryMode = false;
    }
}
