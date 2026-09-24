package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.haylee.textloop.PostgreSQLTestConfiguration;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class ReminderCreationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReminderRepository reminderRepository;

    @BeforeEach
    void clearReminders() {
        reminderRepository.deleteAll();
    }

    @Test
    void validRequestIsPersistedAndReturned() {
        LocalDateTime scheduledAt = LocalDateTime.now().plusDays(1).withNano(0);
        CreateReminderRequest request = new CreateReminderRequest(
                "Take a walk",
                scheduledAt,
                "+12035550100");

        ResponseEntity<ReminderResponse> response = restTemplate.postForEntity(
                "/api/reminders",
                request,
                ReminderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Take a walk");
        assertThat(response.getBody().scheduledAt()).isEqualTo(scheduledAt);
        assertThat(response.getBody().phoneNumber()).isEqualTo("+12035550100");
        assertThat(response.getBody().status()).isEqualTo(ReminderStatus.PENDING);

        List<Reminder> reminders = reminderRepository.findAll();
        assertThat(reminders).hasSize(1);

        Reminder savedReminder = reminders.getFirst();
        assertThat(savedReminder.getId()).isEqualTo(response.getBody().id());
        assertThat(savedReminder.getMessage()).isEqualTo("Take a walk");
        assertThat(savedReminder.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(savedReminder.getPhoneNumber()).isEqualTo("+12035550100");
        assertThat(savedReminder.getStatus()).isEqualTo(ReminderStatus.PENDING);
    }

    @Test
    void invalidRequestDoesNotCreateReminder() {
        CreateReminderRequest request = new CreateReminderRequest(
                " ",
                LocalDateTime.now().plusDays(1),
                "+12035550100");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/reminders",
                request,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reminderRepository.count()).isZero();
    }
}
