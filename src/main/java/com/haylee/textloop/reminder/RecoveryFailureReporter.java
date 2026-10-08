package com.haylee.textloop.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootExceptionReporter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Report recovery failures without logging their messages or causes. The failure still propagates. */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecoveryFailureReporter implements SpringBootExceptionReporter {
    private static final Logger logger = LoggerFactory.getLogger(RecoveryFailureReporter.class);
    private final ConfigurableApplicationContext context;

    public RecoveryFailureReporter(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public boolean reportException(Throwable failure) {
        if (context == null || !context.getEnvironment().getProperty("textloop.recovery", Boolean.class, false)) {
            return false;
        }
        reportFailure();
        return true;
    }

    static void reportFailure() {
        logger.error("Recovery startup or execution failed. Inspect delivery state before retrying.");
    }
}
