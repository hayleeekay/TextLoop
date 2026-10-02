package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haylee.textloop.PostgreSQLTestConfiguration;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ReminderCreationIntegrationTest {

    private static final String PRIVATE_MESSAGE = "private-message-marker";
    private static final String PHONE_NUMBER = "+12035550100";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearReminders() {
        reminderRepository.deleteAll();
    }

    @ParameterizedTest
    @MethodSource("acceptedInputs")
    void validRequestIsPersistedAndReturned(String message, String phoneNumber) {
        LocalDateTime scheduledAt = LocalDateTime.now().plusDays(1).withNano(0);
        CreateReminderRequest request = new CreateReminderRequest(
                message,
                scheduledAt,
                phoneNumber);

        ResponseEntity<ReminderResponse> response = restTemplate.postForEntity(
                "/api/reminders",
                request,
                ReminderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(message);
        assertThat(response.getBody().scheduledAt()).isEqualTo(scheduledAt);
        assertThat(response.getBody().phoneNumber()).isEqualTo(phoneNumber);
        assertThat(response.getBody().status()).isEqualTo(ReminderStatus.PENDING);

        List<Reminder> reminders = reminderRepository.findAll();
        assertThat(reminders).hasSize(1);

        Reminder savedReminder = reminders.getFirst();
        assertThat(savedReminder.getId()).isEqualTo(response.getBody().id());
        assertThat(savedReminder.getMessage()).isEqualTo(message);
        assertThat(savedReminder.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(savedReminder.getPhoneNumber()).isEqualTo(phoneNumber);
        assertThat(savedReminder.getStatus()).isEqualTo(ReminderStatus.PENDING);
    }

    private static Stream<Arguments> acceptedInputs() {
        return Stream.of(
                Arguments.of("Take a walk", PHONE_NUMBER),
                Arguments.of("m".repeat(1000), PHONE_NUMBER),
                Arguments.of("Take a walk", "p".repeat(255)),
                Arguments.of("\uD83D\uDE00".repeat(500), PHONE_NUMBER),
                Arguments.of("Take a walk", "\uD83D\uDE00".repeat(127) + "p"),
                Arguments.of("  Take a walk  ", " +1 (203) 555-0100 "));
    }

    @ParameterizedTest
    @MethodSource("oversizedInputs")
    void oversizedInputReturnsPrivateBadRequestWithoutPersistence(
            String message, String phoneNumber, CapturedOutput output) throws Exception {
        CreateReminderRequest request = new CreateReminderRequest(
                message, LocalDateTime.now().plusDays(1), phoneNumber);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/reminders", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = objectMapper.readTree(response.getBody());
        assertThat(error.path("status").asInt()).isEqualTo(400);
        assertThat(error.path("error").asText()).isEqualTo("Bad Request");
        assertThat(error.path("path").asText()).isEqualTo("/api/reminders");
        assertThat(error.has("message")).isFalse();
        assertThat(error.has("errors")).isFalse();
        assertThat(error.has("trace")).isFalse();
        assertThat(response.getBody()).doesNotContain(message, phoneNumber);
        assertThat(output).doesNotContain(message, phoneNumber, PRIVATE_MESSAGE, PHONE_NUMBER);
        assertThat(reminderRepository.count()).isZero();
    }

    private static Stream<Arguments> oversizedInputs() {
        return Stream.of(
                Arguments.of(PRIVATE_MESSAGE + "m".repeat(1001 - PRIVATE_MESSAGE.length()), PHONE_NUMBER),
                Arguments.of(PRIVATE_MESSAGE, PHONE_NUMBER + "p".repeat(256 - PHONE_NUMBER.length())),
                Arguments.of("\uD83D\uDE00".repeat(500) + "m", PHONE_NUMBER),
                Arguments.of(PRIVATE_MESSAGE, "\uD83D\uDE00".repeat(127) + "pp"));
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
