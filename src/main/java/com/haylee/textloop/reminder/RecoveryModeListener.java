package com.haylee.textloop.reminder;

import java.util.Map;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;

/** Recovery has its own safe failure boundary, including failures before runners execute. */
public class RecoveryModeListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    private boolean recoveryMode;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        recoveryMode = event.getEnvironment().getProperty("textloop.recovery", Boolean.class, false);
        if (recoveryMode) {
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
        return LoggingApplicationListener.DEFAULT_ORDER - 1;
    }

    boolean isRecoveryMode() {
        return recoveryMode;
    }

    void reset() {
        recoveryMode = false;
    }
}
