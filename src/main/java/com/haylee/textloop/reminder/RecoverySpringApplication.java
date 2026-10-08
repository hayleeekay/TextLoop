package com.haylee.textloop.reminder;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Recovery failure reporting covers configuration loading and binding before a context exists. */
public class RecoverySpringApplication extends SpringApplication {
    private final RecoveryModeListener earlyRecoveryMode = new RecoveryModeListener(true);
    private final RecoveryModeListener recoveryMode = new RecoveryModeListener();
    private ConfigurableApplicationContext startupContext;

    public RecoverySpringApplication(Class<?>... primarySources) {
        super(primarySources);
        // Inspect explicit options before loading, then include settings from successfully loaded files.
        addListeners(earlyRecoveryMode, recoveryMode);
    }

    @Override
    public ConfigurableApplicationContext run(String... args) {
        earlyRecoveryMode.reset();
        recoveryMode.reset();
        startupContext = null;
        try {
            return super.run(args);
        } catch (RuntimeException failure) {
            if (!earlyRecoveryMode.isRecoveryMode() && !recoveryMode.isRecoveryMode()) {
                throw failure;
            }
            // Boot's reporter handles context-backed failures; configuration loading/binding needs this fallback.
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
