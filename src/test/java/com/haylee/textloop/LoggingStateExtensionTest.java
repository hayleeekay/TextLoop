package com.haylee.textloop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

@ExtendWith(LoggingStateExtension.class)
class LoggingStateExtensionTest {
    @Test
    void restoresRootAndExistingLevelsAndClearsNewLoggerOverrides() {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        var root = logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME).getConfiguredLevel();
        var spring = logging.getLoggerConfiguration("org.springframework").getConfiguredLevel();
        LoggingStateExtension snapshot = new LoggingStateExtension();
        snapshot.beforeEach(null);
        try {
            logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.WARN);
            logging.setLogLevel("org.springframework", LogLevel.OFF);
            logging.setLogLevel("test.temporary.logging", LogLevel.ERROR);
        } finally {
            snapshot.afterEach(null);
        }
        assertThat(logging.getLoggerConfiguration(LoggingSystem.ROOT_LOGGER_NAME).getConfiguredLevel()).isEqualTo(root);
        assertThat(logging.getLoggerConfiguration("org.springframework").getConfiguredLevel()).isEqualTo(spring);
        assertThat(logging.getLoggerConfiguration("test.temporary.logging").getConfiguredLevel()).isNull();
    }
}
