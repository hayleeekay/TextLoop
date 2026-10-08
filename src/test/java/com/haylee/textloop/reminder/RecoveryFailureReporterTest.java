package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;

import com.haylee.textloop.LoggingStateExtension;

@ExtendWith(LoggingStateExtension.class)
class RecoveryFailureReporterTest {
    @Test
    void leavesNormalApplicationFailureReportingUnchanged() {
        try (var context = new GenericApplicationContext()) {
            assertThat(new RecoveryFailureReporter(context).reportException(new IllegalStateException())).isFalse();
        }
        assertThat(new RecoveryFailureReporter(null).reportException(new IllegalStateException())).isFalse();
    }

    @Test
    void normalApplicationStartupFailureKeepsItsOriginalCause() {
        var application = new RecoverySpringApplication(FailingConfiguration.class);
        application.setLogStartupInfo(false);
        assertThatThrownBy(() -> application.run("--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off", "--logging.level.org.springframework=OFF"))
                .isNotInstanceOf(RecoveryFailureException.class)
                .hasRootCauseMessage("Normal startup failure");
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingConfiguration {
        @Bean Object failStartup() {
            throw new IllegalStateException("Normal startup failure");
        }
    }

}
