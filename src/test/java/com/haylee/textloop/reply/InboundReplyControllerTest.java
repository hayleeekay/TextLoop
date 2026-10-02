package com.haylee.textloop.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.haylee.textloop.reply.dto.InboundReplyRequest;

@WebMvcTest(InboundReplyController.class)
@ExtendWith(OutputCaptureExtension.class)
class InboundReplyControllerTest {
    private static final String PHONE_NUMBER = "+12035550100";
    private static final String REPLY_BODY = "private-reply-marker";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InboundReplyService inboundReplyService;

    @ParameterizedTest
    @ValueSource(strings = {"  dOnE  ", "an arbitrary reply"})
    void receivesReplyWithoutChangingInput(String body) throws Exception {
        String requestBody = """
                {"fromPhoneNumber": " %s ", "body": "%s"}
                """.formatted(PHONE_NUMBER, body);

        mockMvc.perform(post("/api/inbound-replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        Mockito.verify(inboundReplyService).receiveReply(new InboundReplyRequest(" " + PHONE_NUMBER + " ", body));
        Mockito.verifyNoMoreInteractions(inboundReplyService);
    }

    @ParameterizedTest
    @MethodSource("invalidFieldRequests")
    void rejectsInvalidFieldsWithoutExposingInput(String requestBody, CapturedOutput output) throws Exception {
        assertPrivateBadRequest(requestBody, output);
    }

    private static Stream<String> invalidFieldRequests() {
        return Stream.of("fromPhoneNumber", "body").flatMap(field -> {
            String otherField = field.equals("fromPhoneNumber") ? "body" : "fromPhoneNumber";
            String otherValue = field.equals("fromPhoneNumber") ? REPLY_BODY : PHONE_NUMBER;
            String missingField = "{\"%s\":\"%s\"}".formatted(otherField, otherValue);
            Stream<String> invalidValues = Stream.of("null", "\"\"", "\" \\t\\n \"")
                    .map(value -> "{\"%s\":%s,\"%s\":\"%s\"}"
                            .formatted(field, value, otherField, otherValue));
            return Stream.concat(Stream.of(missingField), invalidValues);
        });
    }

    @ParameterizedTest
    @MethodSource("unreadableRequests")
    void rejectsUnreadableRequestsWithoutExposingInput(String requestBody, CapturedOutput output) throws Exception {
        assertPrivateBadRequest(requestBody, output);
    }

    private static Stream<String> unreadableRequests() {
        return Stream.of(
                "",
                "null",
                "{\"fromPhoneNumber\":\"%s\",\"body\":\"%s\"".formatted(PHONE_NUMBER, REPLY_BODY),
                "{\"fromPhoneNumber\":\"%s\",\"body\":{\"text\":\"%s\"}}".formatted(PHONE_NUMBER, REPLY_BODY),
                "{\"fromPhoneNumber\":{\"value\":\"%s\"},\"body\":\"%s\"}".formatted(PHONE_NUMBER, REPLY_BODY),
                "[{\"fromPhoneNumber\":\"%s\",\"body\":\"%s\"}]".formatted(PHONE_NUMBER, REPLY_BODY));
    }

    private void assertPrivateBadRequest(String requestBody, CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/inbound-replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(""));

        Mockito.verifyNoInteractions(inboundReplyService);
        assertThat(output).doesNotContain(PHONE_NUMBER, REPLY_BODY);
    }

    @Test
    void doesNotHandleUnrelatedServiceFailures() {
        RuntimeException failure = new IllegalStateException("Receipt failed");
        Mockito.doThrow(failure).when(inboundReplyService).receiveReply(Mockito.any(InboundReplyRequest.class));

        assertThatThrownBy(() -> mockMvc.perform(post("/api/inbound-replies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromPhoneNumber\":\"%s\",\"body\":\"%s\"}"
                                .formatted(PHONE_NUMBER, REPLY_BODY))))
                .hasCause(failure);
    }
}
