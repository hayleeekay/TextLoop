package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

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
import com.haylee.textloop.reminder.dto.ReminderResponse;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class ReminderRetrievalIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReminderRepository reminderRepository;

    @BeforeEach
    void clearReminders() {
        reminderRepository.deleteAll();
    }

    @Test
    void returnsPersistedReminders() {
        Reminder firstReminder = reminderRepository.save(new Reminder(
                "Take a walk",
                "+12035550100",
                LocalDateTime.now().plusHours(1).withNano(0)));
        Reminder secondReminder = reminderRepository.save(new Reminder(
                "Drink water",
                "+12035550101",
                LocalDateTime.now().plusHours(2).withNano(0)));

        ResponseEntity<ReminderResponse[]> response = restTemplate.getForEntity(
                "/api/reminders",
                ReminderResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactlyInAnyOrder(
                responseFrom(firstReminder),
                responseFrom(secondReminder));
    }

    @Test
    void returnsEmptyArrayWhenNoRemindersExist() {
        ResponseEntity<ReminderResponse[]> response = restTemplate.getForEntity(
                "/api/reminders",
                ReminderResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    private ReminderResponse responseFrom(Reminder reminder) {
        return new ReminderResponse(
                reminder.getId(),
                reminder.getMessage(),
                reminder.getScheduledAt(),
                reminder.getPhoneNumber(),
                reminder.getStatus());
    }
}
