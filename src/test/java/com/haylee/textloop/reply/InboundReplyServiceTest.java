package com.haylee.textloop.reply;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.haylee.textloop.reply.dto.InboundReplyRequest;

@ExtendWith(OutputCaptureExtension.class)
class InboundReplyServiceTest {
    @Test
    void logsReceiptWithoutPayload(CapturedOutput output) {
        InboundReplyService service = new InboundReplyService();
        InboundReplyRequest request = new InboundReplyRequest("+12035550100", "private-reply-marker");

        service.receiveReply(request);

        assertThat(output)
                .containsOnlyOnce("Simulated inbound reply received")
                .doesNotContain(request.fromPhoneNumber(), request.body());
    }
}
