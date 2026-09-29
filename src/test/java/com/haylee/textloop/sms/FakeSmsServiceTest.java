package com.haylee.textloop.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class FakeSmsServiceTest {

    @Test
    void logsSimulatedSms(CapturedOutput output) {
        FakeSmsService smsService = new FakeSmsService();

        smsService.sendSms("+12035550100", "TextLoop Reminder:\nTake a walk\n\nReply DONE, SNOOZE, or CANCEL.");

        assertThat(output)
                .contains("[FAKE SMS]")
                .contains("To: +12035550100")
                .contains("Message: TextLoop Reminder:")
                .contains("Take a walk")
                .contains("DONE, SNOOZE, or CANCEL");
    }
}
