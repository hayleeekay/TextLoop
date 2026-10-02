package com.haylee.textloop.reply;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.haylee.textloop.PostgreSQLTestConfiguration;
import com.haylee.textloop.reminder.Reminder;
import com.haylee.textloop.reminder.ReminderRepository;
import com.haylee.textloop.reminder.ReminderStatus;
import com.haylee.textloop.reply.dto.InboundReplyRequest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class InboundReplyIntegrationTest {
    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReminderRepository reminderRepository;

    @BeforeEach
    void seedReminders() {
        reminderRepository.deleteAll();
        Reminder sent = new Reminder("Take a walk", "+12035550100", LocalDateTime.now().minusHours(1));
        sent.setStatus(ReminderStatus.SENT);
        reminderRepository.save(sent);
        reminderRepository.save(new Reminder("Drink water", "+12035550100", LocalDateTime.now().plusDays(1)));
    }

    @ParameterizedTest
    @CsvSource({"DONE, NO_CONTENT", "' ', BAD_REQUEST"})
    void requestsLeavePersistedRemindersUnchanged(String body, HttpStatus expectedStatus) {
        List<ReminderSnapshot> before = snapshotReminders();
        long countBefore = reminderRepository.count();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/inbound-replies",
                new InboundReplyRequest("+12035550100", body),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNull();
        assertThat(reminderRepository.count()).isEqualTo(countBefore);
        assertThat(snapshotReminders()).containsExactlyInAnyOrderElementsOf(before);
    }

    private List<ReminderSnapshot> snapshotReminders() {
        return reminderRepository.findAll().stream()
                .map(reminder -> new ReminderSnapshot(
                        reminder.getId(),
                        reminder.getMessage(),
                        reminder.getPhoneNumber(),
                        reminder.getScheduledAt(),
                        reminder.getStatus(),
                        reminder.getCreatedAt(),
                        reminder.getUpdatedAt()))
                .toList();
    }

    private record ReminderSnapshot(
            Long id,
            String message,
            String phoneNumber,
            LocalDateTime scheduledAt,
            ReminderStatus status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
