package com.haylee.textloop.reply;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.haylee.textloop.PostgreSQLTestConfiguration;
import com.haylee.textloop.reminder.Reminder;
import com.haylee.textloop.reminder.ReminderRepository;
import com.haylee.textloop.reminder.ReminderStatus;
import com.haylee.textloop.reminder.DeliveryTransactions;
import com.haylee.textloop.reminder.SimulatedReply;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reply.dto.InboundReplyRequest;
import com.haylee.textloop.sms.SmsSubmissionResult;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class InboundReplyIntegrationTest {
    private static final String NUMBER = "+12035550100";
    private static final String SNOOZE_SETTINGS = "/api/settings/snooze-duration";
    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private DeliveryTransactions deliveries;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedReminders() {
        reminderRepository.deleteAll();
        jdbc.update("delete from snooze_replies");
        jdbc.update("delete from snooze_settings");
        jdbc.update("delete from reminder_number");
        Reminder sent = new Reminder("Take a walk", "+12035550100", LocalDateTime.now().minusHours(1));
        sent.setStatus(ReminderStatus.SENT);
        reminderRepository.save(sent);
        reminderRepository.save(new Reminder("Drink water", "+12035550100", LocalDateTime.now().plusDays(1)));
    }

    @Test
    void usesAdvertisedDurationAndLimitsHintsAndSuccessfulSnoozes() {
        assertThat(restTemplate.getForObject(SNOOZE_SETTINGS, String.class)).contains("\"minutes\":30");
        assertThat(setDuration(45)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(setDuration(60)).isEqualTo(HttpStatus.OK);
        var original = claim("Call Mom");
        assertThat(original.message()).endsWith("Need a little more time? Reply SNOOZE for 1 hour.");
        accept(original);
        assertThat(setDuration(15)).isEqualTo(HttpStatus.OK);
        assertThat(reply(UUID.randomUUID(), "STOP").replyText()).isNull();
        assertThat(reply(UUID.randomUUID(), "snoooze").replyText()).isEqualTo("I didn't catch that. Try SNOOZE.");
        assertThat(reply(UUID.randomUUID(), "snoooze").replyText()).isNull();

        var eventId = UUID.randomUUID();
        var first = reply(eventId, "  sNoOzE ");
        assertThat(first.replyText()).isEqualTo("Okay, I'll remind you again in 1 hour.");
        assertThat(first.scheduledAt()).isBetween(LocalDateTime.now().plusMinutes(59), LocalDateTime.now().plusHours(1));
        assertThat(reply(eventId, "SNOOZE"))
                .isEqualTo(new SimulatedReply(first.replyText(), first.scheduledAt(), true));
        assertThat(reply(UUID.randomUUID(), "SNOOZE").replyText()).isNull();
        assertThat(reminderRepository.findAll()).filteredOn(row -> row.getMessage().equals("Call Mom"))
                .extracting(Reminder::getStatus).containsExactlyInAnyOrder(ReminderStatus.SENT, ReminderStatus.PENDING);
    }

    @Test
    void stopsAtNewerUncertainDeliveryThenSnoozesItOnlyOnceWhenAccepted() throws Exception {
        accept(claim("Older text"));
        var latest = claim("Latest text");
        assertThat(reply(UUID.randomUUID(), "SNOOZE").scheduledAt()).isNull();
        assertThat(reminderRepository.count()).isEqualTo(4);
        accept(latest);

        var first = CompletableFuture.supplyAsync(() -> reply(UUID.randomUUID(), "SNOOZE"));
        var second = CompletableFuture.supplyAsync(() -> reply(UUID.randomUUID(), "SNOOZE"));
        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .filteredOn(result -> result.scheduledAt() != null).hasSize(1);
        assertThat(reminderRepository.findAll()).filteredOn(row -> row.getMessage().equals("Latest text"))
                .hasSize(2);
        assertThat(reminderRepository.findAll()).filteredOn(row -> row.getMessage().equals("Older text"))
                .hasSize(1);
    }

    @Test
    void oldNumberCannotCreateSnoozesAfterSettingsChanges() {
        accept(claim("Before number change"));
        deliveries.setReminderNumber("+12035550101");
        assertThat(reply(UUID.randomUUID(), "SNOOZE").scheduledAt()).isNull();
        assertThat(reminderRepository.count()).isEqualTo(3);
    }

    private HttpStatus setDuration(int minutes) {
        return HttpStatus.valueOf(restTemplate.exchange(SNOOZE_SETTINGS, HttpMethod.PUT,
                new HttpEntity<>(Map.of("minutes", minutes)), String.class).getStatusCode().value());
    }

    private DeliveryTransactions.Submission claim(String message) {
        var due = LocalDateTime.now().plusMinutes(5);
        Reminder reminder = deliveries.createReminder(new CreateReminderRequest(message, due, NUMBER));
        return deliveries.claim(reminder.getCurrentDeliveryId(), due).orElseThrow();
    }

    private void accept(DeliveryTransactions.Submission submission) {
        deliveries.recordResult(submission.deliveryId(), submission.attemptCount(), SmsSubmissionResult.accepted(null));
        deliveries.finalizeAccepted(submission.deliveryId());
    }

    private SimulatedReply reply(UUID id, String body) {
        var response = restTemplate.postForEntity("/api/inbound-replies",
                new InboundReplyRequest(NUMBER, body, id), SimulatedReply.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @ParameterizedTest
    @CsvSource({
            "DONE, NO_CONTENT",
            "'  cAnCeL  ', NO_CONTENT",
            "snooze, NO_CONTENT",
            "SNOOZE 30, NO_CONTENT",
            "DONE please, NO_CONTENT",
            "DONE!, NO_CONTENT",
            "SCHEDULE, NO_CONTENT",
            "an arbitrary reply, NO_CONTENT",
            "head\0tail, NO_CONTENT",
            "' ', BAD_REQUEST"
    })
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
