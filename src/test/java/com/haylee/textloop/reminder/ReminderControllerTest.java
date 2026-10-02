package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;

@WebMvcTest(ReminderController.class)
class ReminderControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReminderService reminderService;

    @ParameterizedTest
    @ValueSource(ints = {0, 30})
    void createsReminder(int second) throws Exception {
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(1).withSecond(second).withNano(0);

        String requestBody = """
                {
                  "message": "Take a walk",
                  "scheduledAt": "%s",
                  "phoneNumber": "+12035550100"
                }
                """.formatted(scheduledAt);

        ReminderResponse serviceResponse = new ReminderResponse(
                1L,
                "Take a walk",
                scheduledAt,
                "+12035550100",
                ReminderStatus.PENDING);

        Mockito.when(
                reminderService.createReminder(Mockito.any(CreateReminderRequest.class)))
                .thenReturn(serviceResponse);

        mockMvc.perform(
                post("/api/reminders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.message").value("Take a walk"))
                .andExpect(jsonPath("$.scheduledAt").value(scheduledAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
                .andExpect(jsonPath("$.phoneNumber").value("+12035550100"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        Mockito.verify(reminderService).createReminder(Mockito.argThat(request ->
                request.message().equals("Take a walk")
                        && request.scheduledAt().equals(scheduledAt)
                        && request.phoneNumber().equals("+12035550100")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 30})
    void getsReminders(int second) throws Exception {
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(1).withSecond(second).withNano(0);
        ReminderResponse serviceResponse = new ReminderResponse(
                1L,
                "Take a walk",
                scheduledAt,
                "+12035550100",
                ReminderStatus.PENDING);

        Mockito.when(reminderService.getReminders())
                .thenReturn(List.of(serviceResponse));

        mockMvc.perform(get("/api/reminders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].message").value("Take a walk"))
                .andExpect(jsonPath("$[0].scheduledAt").value(scheduledAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
                .andExpect(jsonPath("$[0].phoneNumber").value("+12035550100"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        Mockito.verify(reminderService).getReminders();
    }

    @Test
    void getsEmptyReminderList() throws Exception {
        Mockito.when(reminderService.getReminders()).thenReturn(List.of());

        mockMvc.perform(get("/api/reminders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        Mockito.verify(reminderService).getReminders();
    }

    @Test
    void rejectsPastScheduledTime() throws Exception {
        String requestBody = """
                {
                  "message": "Take a walk",
                  "scheduledAt": "2000-01-01T10:00:00",
                  "phoneNumber": "+12035550100"
                }
                """;

        assertBadRequest(requestBody);
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        String requestBody = """
                {
                  "message": "   ",
                  "scheduledAt": "%s",
                  "phoneNumber": "+12035550100"
                }
                """.formatted(LocalDateTime.now().plusHours(1));

        assertBadRequest(requestBody);
    }

    @Test
    void rejectsBlankPhoneNumber() throws Exception {
        String requestBody = """
                {
                  "message": "Take a walk",
                  "scheduledAt": "%s",
                  "phoneNumber": "   "
                }
                """.formatted(LocalDateTime.now().plusHours(1));

        assertBadRequest(requestBody);
    }

    @Test
    void rejectsNullScheduledTime() throws Exception {
        String requestBody = """
                {
                  "message": "Take a walk",
                  "scheduledAt": null,
                  "phoneNumber": "+12035550100"
                }
                """;

        assertBadRequest(requestBody);
    }

    @ParameterizedTest
    @MethodSource("missingOrNullStrings")
    void rejectsMissingOrNullStrings(String fields) throws Exception {
        assertBadRequest("{\"scheduledAt\":\"%s\",%s}"
                .formatted(LocalDateTime.now().plusDays(1), fields));
    }

    private static Stream<String> missingOrNullStrings() {
        return Stream.of(
                "\"phoneNumber\":\"+12035550100\"",
                "\"phoneNumber\":\"+12035550100\",\"message\":null",
                "\"message\":\"Take a walk\"",
                "\"message\":\"Take a walk\",\"phoneNumber\":null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"message", "phoneNumber"})
    void rejectsOversizedInputBeforeCallingService(String field) throws Exception {
        String message = field.equals("message") ? "m".repeat(1001) : "Take a walk";
        String phoneNumber = field.equals("phoneNumber") ? "p".repeat(256) : "+12035550100";
        assertBadRequest("{\"message\":\"%s\",\"phoneNumber\":\"%s\",\"scheduledAt\":\"%s\"}"
                .formatted(message, phoneNumber, LocalDateTime.now().plusDays(1)));
    }

    @Test
    void doesNotHandleUnrelatedServiceFailures() {
        RuntimeException failure = new IllegalStateException("Creation failed");
        Mockito.when(reminderService.createReminder(Mockito.any(CreateReminderRequest.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> mockMvc.perform(post("/api/reminders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Take a walk\",\"phoneNumber\":\"+12035550100\",\"scheduledAt\":\"%s\"}"
                                .formatted(LocalDateTime.now().plusDays(1)))))
                .hasCause(failure);
    }

    private void assertBadRequest(String requestBody) throws Exception {
        mockMvc.perform(
                post("/api/reminders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(reminderService);
    }
}
