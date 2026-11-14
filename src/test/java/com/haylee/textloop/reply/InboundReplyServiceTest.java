package com.haylee.textloop.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.haylee.textloop.reply.dto.InboundReplyRequest;
import com.haylee.textloop.reminder.DeliveryTransactions;

@ExtendWith(OutputCaptureExtension.class)
class InboundReplyServiceTest {
    @Test
    void logsReceiptWithoutPayload(CapturedOutput output) {
        InboundReplyService service = new InboundReplyService(mock(DeliveryTransactions.class));
        InboundReplyRequest request = new InboundReplyRequest("+12035550100", "private-reply-marker");

        service.receiveReply(request);

        assertThat(output)
                .containsOnlyOnce("Simulated inbound reply received")
                .doesNotContain(request.fromPhoneNumber(), request.body());
    }
}
