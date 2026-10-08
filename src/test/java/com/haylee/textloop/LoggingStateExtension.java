package com.haylee.textloop;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

/** Application contexts share JVM logging state; closing one does not restore its logger levels. */
public class LoggingStateExtension implements BeforeEachCallback, AfterEachCallback {
    private LoggingSystem logging;
    private final Map<String, LogLevel> levels = new HashMap<>();

    @Override
    public void beforeEach(ExtensionContext context) {
        logging = LoggingSystem.get(getClass().getClassLoader());
        logging.getLoggerConfigurations().forEach(logger -> levels.put(logger.getName(), logger.getConfiguredLevel()));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        logging.getLoggerConfigurations().forEach(logger -> logging.setLogLevel(logger.getName(), levels.get(logger.getName())));
        levels.clear();
    }
}
