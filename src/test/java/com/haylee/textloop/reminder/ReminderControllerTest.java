package com.haylee.textloop.reminder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
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

    @Test
    void createsReminder() throws Exception {
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(1);

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
                .andExpect(jsonPath("$.scheduledAt").value(scheduledAt.toString()))
                .andExpect(jsonPath("$.phoneNumber").value("+12035550100"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        Mockito.verify(reminderService).createReminder(Mockito.argThat(request ->
                request.message().equals("Take a walk")
                        && request.scheduledAt().equals(scheduledAt)
                        && request.phoneNumber().equals("+12035550100")));
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

    private void assertBadRequest(String requestBody) throws Exception {
        mockMvc.perform(
                post("/api/reminders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(reminderService);
    }
}
