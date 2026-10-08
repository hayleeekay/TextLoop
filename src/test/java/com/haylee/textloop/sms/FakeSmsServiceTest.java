package com.haylee.textloop.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.haylee.textloop.LoggingStateExtension;

@ExtendWith({LoggingStateExtension.class, OutputCaptureExtension.class})
class FakeSmsServiceTest {
    @BeforeEach
    void enableSenderOutput() {
        LoggingSystem.get(getClass().getClassLoader()).setLogLevel(FakeSmsService.class.getName(), LogLevel.INFO);
    }

    @Test
    void reportsSimulatedAcceptanceWithoutPayload(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        var result = new FakeSmsService().sendSms(id, "+12035550100", "private-reminder-marker");
        assertThat(result.outcome()).isEqualTo(SmsSubmissionResult.Outcome.ACCEPTED);
        assertThat(output).contains("[FAKE SMS] Simulated acceptance", id.toString())
                .doesNotContain("+12035550100", "private-reminder-marker");
    }
}
