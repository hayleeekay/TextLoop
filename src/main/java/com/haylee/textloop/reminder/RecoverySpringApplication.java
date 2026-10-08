package com.haylee.textloop.reminder;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Recovery failure reporting also covers configuration binding before a context exists. */
public class RecoverySpringApplication extends SpringApplication {
    private final RecoveryModeListener recoveryMode = new RecoveryModeListener();
    private ConfigurableApplicationContext startupContext;

    public RecoverySpringApplication(Class<?>... primarySources) {
        super(primarySources);
        addListeners(recoveryMode);
    }

    @Override
    public ConfigurableApplicationContext run(String... args) {
        recoveryMode.reset();
        startupContext = null;
        try {
            return super.run(args);
        } catch (RuntimeException failure) {
            if (!recoveryMode.isRecoveryMode()) {
                throw failure;
            }
            // Boot's reporter handles context-backed failures; early binding failures need this fallback.
            if (startupContext == null
                    || !startupContext.getEnvironment().getProperty("textloop.recovery", Boolean.class, false)) {
                RecoveryFailureReporter.reportFailure();
            }
            throw new RecoveryFailureException();
        }
    }

    @Override
    protected ConfigurableApplicationContext createApplicationContext() {
        startupContext = super.createApplicationContext();
        return startupContext;
    }
}
