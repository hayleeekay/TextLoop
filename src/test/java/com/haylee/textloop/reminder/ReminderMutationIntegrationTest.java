package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.haylee.textloop.PostgreSQLTestConfiguration;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.EditReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;
import com.haylee.textloop.reminder.dto.WithdrawReminderRequest;
import com.haylee.textloop.sms.SmsSubmissionResult;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({PostgreSQLTestConfiguration.class, ReminderMutationIntegrationTest.PreciseClock.class})
@ExtendWith(OutputCaptureExtension.class)
class ReminderMutationIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired ReminderRepository reminders;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryTransactions transactions;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class PreciseClock {
        @Bean @Primary Clock preciseClock() {
            return Clock.fixed(Instant.parse("2026-10-22T12:00:00.123456789Z"), ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void clearRows() {
        reminders.deleteAll();
    }

    private ReminderResponse create() {
        return http.postForEntity("/api/reminders", new CreateReminderRequest(
                "Original message", LocalDateTime.now().plusDays(1).withNano(0), "+12035550100"),
                ReminderResponse.class).getBody();
    }

    private String path(Long id, boolean withdraw) {
        return "/api/reminders/" + id + (withdraw ? "/withdraw" : "");
    }

    private ResponseEntity<String> mutate(Long id, boolean withdraw, Object request) {
        return http.exchange(path(id, withdraw), withdraw ? HttpMethod.POST : HttpMethod.PUT,
                new HttpEntity<>(request), String.class);
    }

    private Object request(ReminderResponse reminder, boolean withdraw) {
        return withdraw ? new WithdrawReminderRequest(reminder.version())
                : new EditReminderRequest("Corrected message", reminder.scheduledAt().plusHours(1), reminder.version());
    }

    @Test
    void editReturnsCommittedVersionAndReplacesSnapshotWithoutChangingPhone() {
        ReminderResponse created = create();
        Reminder original = reminders.findById(created.id()).orElseThrow();
        Delivery old = deliveries.findById(original.getCurrentDeliveryId()).orElseThrow();
        assertThat(created.version()).isEqualTo(original.getVersion());
        assertThat(created.withdrawnAt()).isNull();

        var response = http.exchange(path(created.id(), false), HttpMethod.PUT,
                new HttpEntity<>(request(created, false)), ReminderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReminderResponse edited = response.getBody();
        Reminder saved = reminders.findById(created.id()).orElseThrow();
        Delivery current = deliveries.findById(saved.getCurrentDeliveryId()).orElseThrow();
        assertThat(edited.version()).isEqualTo(saved.getVersion()).isGreaterThan(created.version());
        assertThat(edited.message()).isEqualTo("Corrected message");
        assertThat(edited.phoneNumber()).isEqualTo(created.phoneNumber());
        assertThat(current.getReplacesDeliveryId()).isEqualTo(old.getId());
        assertThat(current.getMessage()).isEqualTo("TextLoop Reminder:\nCorrected message");
        assertThat(deliveries.findById(old.getId()).orElseThrow().getMessage()).isEqualTo(old.getMessage());
        assertThat(deliveries.count()).isEqualTo(2);

        assertThat(mutate(created.id(), false, request(created, false)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(deliveries.count()).isEqualTo(2);
        assertThat(List.of(http.getForObject("/api/reminders", ReminderResponse[].class))).containsExactly(edited);
    }

    @Test
    void withdrawalReturnsCommittedVersionAndPersistsWithoutDeletingEvidence() {
        ReminderResponse created = create();
        var before = reminders.findById(created.id()).orElseThrow();
        var response = http.postForEntity(path(created.id(), true), request(created, true), ReminderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReminderResponse withdrawn = response.getBody();
        Reminder saved = reminders.findById(created.id()).orElseThrow();
        assertThat(withdrawn.withdrawnAt()).isNotNull().isEqualTo(saved.getWithdrawnAt());
        assertThat(withdrawn.withdrawnAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 12, 0, 0, 123456000));
        assertThat(withdrawn.version()).isGreaterThan(created.version()).isEqualTo(saved.getVersion());
        assertThat(withdrawn.status()).isEqualTo(ReminderStatus.PENDING);
        assertThat(saved.getCurrentDeliveryId()).isEqualTo(before.getCurrentDeliveryId());
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(mutate(created.id(), false, request(withdrawn, false)).getBody()).contains("NOT_EDITABLE");
        assertThat(mutate(created.id(), true, request(withdrawn, true)).getBody()).contains("NOT_EDITABLE");
    }

    @Test
    void claimedAcceptedAndSentRemindersReturnNotEditableWithoutChangingEvidence() {
        ReminderResponse created = create();
        var deliveryId = reminders.findById(created.id()).orElseThrow().getCurrentDeliveryId();
        var attempt = transactions.claim(deliveryId, created.scheduledAt()).orElseThrow();
        assertNotEditable(created.id());
        transactions.recordResult(deliveryId, attempt.attemptCount(), SmsSubmissionResult.accepted(null));
        assertNotEditable(created.id());
        transactions.finalizeAccepted(deliveryId);
        assertNotEditable(created.id());
    }

    private void assertNotEditable(Long id) {
        var saved = reminders.findById(id).orElseThrow();
        var delivery = deliveries.findById(saved.getCurrentDeliveryId()).orElseThrow();
        for (boolean withdraw : List.of(false, true)) {
            var response = mutate(id, withdraw, withdraw ? new WithdrawReminderRequest(saved.getVersion())
                    : new EditReminderRequest("Refused", saved.getScheduledAt().plusHours(1), saved.getVersion()));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isEqualTo("{\"code\":\"NOT_EDITABLE\"}");
        }
        var after = reminders.findById(id).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(saved.getVersion());
        assertThat(after.getMessage()).isEqualTo(saved.getMessage());
        assertThat(after.getWithdrawnAt()).isNull();
        assertThat(deliveries.findById(delivery.getId()).orElseThrow())
                .usingRecursiveComparison().ignoringFields("reminder").isEqualTo(delivery);
        assertThat(deliveries.count()).isEqualTo(1);
    }

    static Stream<String> invalidEdits() {
        String future = LocalDateTime.now().plusDays(2).toString();
        return Stream.of(
                "{}", "null", "{", "[]",
                "{\"message\":\"private-invalid-marker\",\"scheduledAt\":\"invalid\",\"expectedVersion\":0}",
                "{\"message\":\"private-invalid-marker\",\"expectedVersion\":0}",
                "{\"message\":\" \",\"scheduledAt\":\"" + future + "\",\"expectedVersion\":0}",
                "{\"message\":\"" + "m".repeat(1001) + "\",\"scheduledAt\":\"" + future + "\",\"expectedVersion\":0}",
                "{\"message\":\"private-invalid-marker\",\"scheduledAt\":\"" + future + "\"}",
                "{\"message\":\"private-invalid-marker\",\"scheduledAt\":\"" + future + "\",\"expectedVersion\":null}",
                "{\"message\":\"private-invalid-marker\",\"scheduledAt\":\"" + future + "\",\"expectedVersion\":-1}",
                "{\"message\":\"private-invalid-marker\",\"scheduledAt\":\"2000-01-01T12:00:00\",\"expectedVersion\":0}");
    }

    @ParameterizedTest
    @MethodSource("invalidEdits")
    void invalidEditLeavesAllRowsUnchangedAndDoesNotExposeInput(String body, CapturedOutput output) {
        ReminderResponse created = create();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.exchange(path(created.id(), false), HttpMethod.PUT,
                new HttpEntity<>(body.replace("\"expectedVersion\":0", "\"expectedVersion\":" + created.version()), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContain("private-invalid-marker", "+12035550100");
        assertThat(output).doesNotContain("private-invalid-marker", "+12035550100");
        assertUnchanged(created);
    }

    @Test
    void invalidWithdrawalsAndMissingIdsHaveUsefulErrors() {
        ReminderResponse created = create();
        for (String body : List.of("{}", "{\"expectedVersion\":null}", "{\"expectedVersion\":-1}")) {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            assertThat(http.exchange(path(created.id(), true), HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        for (boolean withdraw : List.of(false, true)) {
            var response = mutate(Long.MAX_VALUE, withdraw, request(created, withdraw));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isEqualTo("{\"code\":\"NOT_FOUND\"}");
            response = mutate(created.id(), withdraw, withdraw ? new WithdrawReminderRequest(created.version() + 1)
                    : new EditReminderRequest("Stale", created.scheduledAt(), created.version() + 1));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).isEqualTo("{\"code\":\"STALE_VERSION\"}");
        }
        assertUnchanged(created);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void fractionalVersionsAreRejectedWithoutStoredChanges(boolean withdraw, boolean negative, CapturedOutput output) {
        ReminderResponse created = create();
        if (negative) {
            // Upgraded legacy rows start at zero, where truncating -0.9 would otherwise match.
            jdbc.update("update reminders set version = 0 where id = ?", created.id());
        }
        var before = storedRows();
        String version = negative ? "-0.9" : created.version() + ".9";
        String body = withdraw ? "{\"expectedVersion\":" + version + "}"
                : "{\"message\":\"private-invalid-marker\",\"scheduledAt\":\"" + created.scheduledAt()
                        + "\",\"expectedVersion\":" + version + "}";
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.exchange(path(created.id(), withdraw), withdraw ? HttpMethod.POST : HttpMethod.PUT,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContain("private-invalid-marker", "+12035550100");
        assertThat(output).doesNotContain("private-invalid-marker", "+12035550100");
        assertThat(storedRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedPathIdIsRejectedWithoutExposingValueOrChangingStorage(boolean withdraw, CapturedOutput output) {
        ReminderResponse created = create();
        var before = storedRows();
        String url = "/api/reminders/private-id-marker" + (withdraw ? "/withdraw" : "");
        var response = http.exchange(url, withdraw ? HttpMethod.POST : HttpMethod.PUT,
                new HttpEntity<>(request(created, withdraw)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("{\"code\":\"INVALID_ID\"}")
                .doesNotContain("private-id-marker");
        assertThat(output).doesNotContain("private-id-marker");
        assertThat(storedRows()).isEqualTo(before);
    }

    private List<?> storedRows() {
        return List.of(jdbc.queryForList("select * from reminders order by id"),
                jdbc.queryForList("select * from deliveries order by id"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void competingHttpMutationsWithOneVersionHaveOneWinner(boolean secondWithdraws) throws Exception {
        ReminderResponse created = create();
        var start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> afterStart(start, created, false));
        var second = CompletableFuture.supplyAsync(() -> afterStart(start, created, secondWithdraws));
        start.countDown();
        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(reminders.findById(created.id()).orElseThrow().getVersion()).isEqualTo(created.version() + 1);
    }

    private HttpStatus afterStart(CountDownLatch start, ReminderResponse reminder, boolean withdraw) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
            return HttpStatus.valueOf(mutate(reminder.id(), withdraw, request(reminder, withdraw)).getStatusCode().value());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void assertUnchanged(ReminderResponse original) {
        assertThat(List.of(http.getForObject("/api/reminders", ReminderResponse[].class))).containsExactly(original);
        assertThat(deliveries.count()).isEqualTo(1);
    }
}
