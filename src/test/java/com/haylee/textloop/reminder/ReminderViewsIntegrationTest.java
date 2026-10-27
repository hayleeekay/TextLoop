package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.JsonNode;
import com.haylee.textloop.PostgreSQLTestConfiguration;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.EditReminderRequest;
import com.haylee.textloop.reminder.dto.RemindAgainRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;
import com.haylee.textloop.reminder.dto.ReminderViewResponse;
import com.haylee.textloop.reminder.dto.ReminderViewResponse.Feedback;
import com.haylee.textloop.reminder.dto.WithdrawReminderRequest;
import com.haylee.textloop.sms.SmsService;
import com.haylee.textloop.sms.SmsSubmissionResult;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({PostgreSQLTestConfiguration.class, ReminderViewsIntegrationTest.Fixtures.class})
class ReminderViewsIntegrationTest {
    private static final String BASE = "/api/reminders";
    private static final String PHONE = "+12035550100";
    @Autowired TestRestTemplate http;
    @Autowired ReminderRepository reminders;
    @Autowired DeliveryRepository deliveries;
    @Autowired ReminderService service;
    @Autowired DeliveryTransactions transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired ReadBarrier barrier;
    @MockitoBean SmsService sender;

    @BeforeEach
    void reset() {
        barrier.release();
        reminders.deleteAll();
        clock.instant = Instant.parse("2030-01-01T12:00:00.123456789Z");
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private Reminder source(LocalDateTime due) {
        var present = clock.instant;
        clock.instant = due.minusDays(1).toInstant(ZoneOffset.UTC);
        try {
            var response = service.createReminder(new CreateReminderRequest("View test message", due, PHONE));
            return reminders.findById(response.id()).orElseThrow();
        } finally {
            clock.instant = present;
        }
    }
    private ReminderViewResponse detail(Long id) {
        var response = http.getForEntity(BASE + "/" + id, ReminderViewResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
    private List<ReminderViewResponse> view(String name) {
        var response = http.getForEntity(BASE + "/" + name, ReminderViewResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }
    private List<?> rows() {
        return List.of(jdbc.queryForList("select * from reminders order by id"),
                jdbc.queryForList("select * from deliveries order by id"),
                jdbc.queryForList("select * from reminder_reuses order by id"));
    }
    private List<?> sourceRows(Long id) {
        return List.of(jdbc.queryForList("select * from reminders where id = ?", id),
                jdbc.queryForList("select * from deliveries where reminder_id = ? order by id", id));
    }
    private ResponseEntity<JsonNode> edit(ReminderViewResponse source) {
        return http.exchange(BASE + "/" + source.id(), HttpMethod.PUT,
                new HttpEntity<>(new EditReminderRequest("Corrected message", now().plusDays(2), source.version())),
                JsonNode.class);
    }
    private ResponseEntity<JsonNode> withdraw(Long id, long version) {
        return http.postForEntity(BASE + "/" + id + "/withdraw", new WithdrawReminderRequest(version), JsonNode.class);
    }
    private RemindAgainRequest reuseRequest(ReminderViewResponse source) {
        return new RemindAgainRequest(UUID.randomUUID(), source.version(), now().plusDays(1));
    }
    private ResponseEntity<JsonNode> reuse(Long id, RemindAgainRequest request) {
        return http.postForEntity(BASE + "/" + id + "/remind-again", request, JsonNode.class);
    }

    @Test
    void emptyCollectionsAndFixedDetailErrors() {
        assertThat(view("upcoming")).isEmpty();
        assertThat(view("recent")).isEmpty();
        var missing = http.getForEntity(BASE + "/9223372036854775807", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).isEqualTo("{\"code\":\"NOT_FOUND\"}");
        var invalid = http.getForEntity(BASE + "/private-id-marker", String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).isEqualTo("{\"code\":\"INVALID_ID\"}");
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2030-01-01T12:00:00.123456000Z", "2030-01-01T12:00:00.123456789Z",
            "2030-01-01T00:00:00Z", "2030-01-08T00:00:00.000000999Z"})
    void exactRollingWindowBoundariesAndTies(String instant) {
        clock.instant = Instant.parse(instant);
        LocalDateTime end = now().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime start = end.minusDays(7);
        source(start.minusNanos(1000));
        source(start);
        Reminder firstInside = source(start.plusNanos(1000));
        Reminder beforeEnd = source(end.minusNanos(1000));
        Reminder atEnd = source(end);
        Reminder tiedAtEnd = source(end);
        Reminder afterEnd = source(end.plusNanos(1000));
        Reminder tiedAfterEnd = source(end.plusNanos(1000));
        assertThat(view("recent")).extracting(ReminderViewResponse::id)
                .containsExactly(tiedAtEnd.getId(), atEnd.getId(), beforeEnd.getId(), firstInside.getId());
        assertThat(view("upcoming")).extracting(ReminderViewResponse::id)
                .containsExactly(afterEnd.getId(), tiedAfterEnd.getId());
        verifyNoInteractions(sender);
    }

    @Test
    void selectionUsesDueTimeAndUpcomingExcludesWithdrawnAndSent() {
        Reminder old = source(now().minusDays(1));
        jdbc.update("update reminders set created_at = ? where id = ?", now().minusYears(1), old.getId());
        Reminder expired = source(now().minusDays(8));
        Reminder future = source(now().plusDays(1));
        Reminder withdrawn = source(now().plusDays(1));
        transactions.withdrawReminder(withdrawn.getId(), withdrawn.getVersion());
        Reminder sent = source(now().plusDays(1));
        var attempt = transactions.claim(sent.getCurrentDeliveryId(), now().plusDays(2)).orElseThrow();
        transactions.recordResult(attempt.deliveryId(), attempt.attemptCount(), SmsSubmissionResult.accepted(null));
        transactions.finalizeAccepted(attempt.deliveryId());
        assertThat(view("recent")).extracting(ReminderViewResponse::id).containsExactly(old.getId());
        assertThat(view("upcoming")).extracting(ReminderViewResponse::id).containsExactly(future.getId());
        assertThat(detail(expired.getId()).feedback()).isEqualTo(Feedback.WAITING);
        assertThat(detail(withdrawn.getId()).feedback()).isEqualTo(Feedback.WITHDRAWN);
        assertThat(detail(sent.getId()).feedback()).isEqualTo(Feedback.ACCEPTED);
        verifyNoInteractions(sender);
    }

    enum Source { READY, UNKNOWN, ACCEPTED, SENT, WITHDRAWN, PERMANENT, RETRYABLE, EXHAUSTED, EXTRA,
        LEGACY_SENT, LEGACY_PENDING, MISSING, MISMATCH, SENT_READY, WITHDRAWN_ACCEPTED, READY_CLAIMED, RETRY_WITHOUT_TIME }

    private Reminder source(Source state) {
        if (state == Source.LEGACY_SENT || state == Source.LEGACY_PENDING) {
            Reminder reminder = new Reminder("Legacy view message", PHONE, now().minusDays(1));
            if (state == Source.LEGACY_SENT) reminder.setStatus(ReminderStatus.SENT);
            return reminders.save(reminder);
        }
        Reminder reminder = source(now().minusDays(1).truncatedTo(ChronoUnit.MICROS));
        UUID id = reminder.getCurrentDeliveryId();
        switch (state) {
            case WITHDRAWN -> transactions.withdrawReminder(reminder.getId(), reminder.getVersion());
            case MISSING -> jdbc.update("update reminders set current_delivery_id = ? where id = ?", UUID.randomUUID(), reminder.getId());
            case MISMATCH -> {
                Reminder other = source(now().minusDays(8));
                jdbc.update("update reminders set current_delivery_id = ? where id = ?", other.getCurrentDeliveryId(), reminder.getId());
            }
            case SENT_READY -> jdbc.update("update reminders set status = 'SENT' where id = ?", reminder.getId());
            case READY_CLAIMED -> jdbc.update("update deliveries set attempt_count = 1 where id = ?", id);
            case READY -> { }
            default -> {
                var attempt = transactions.claim(id, now()).orElseThrow();
                if (state != Source.UNKNOWN) {
                    var outcome = switch (state) {
                        case PERMANENT -> SmsSubmissionResult.permanentRejection();
                        case RETRYABLE, EXHAUSTED, EXTRA, RETRY_WITHOUT_TIME -> SmsSubmissionResult.retryableRejection();
                        default -> SmsSubmissionResult.accepted(null);
                    };
                    transactions.recordResult(id, attempt.attemptCount(), outcome);
                }
                if (state == Source.EXHAUSTED || state == Source.EXTRA) {
                    for (int i = 0; i < 2; i++) {
                        clock.advance(Duration.ofMinutes(6));
                        attempt = transactions.claim(id, now()).orElseThrow();
                        transactions.recordResult(id, attempt.attemptCount(), SmsSubmissionResult.retryableRejection());
                    }
                    if (state == Source.EXTRA) {
                        transactions.recover(id, deliveries.findById(id).orElseThrow().getVersion(), RecoveryAction.RETRY_ONCE, false);
                    }
                }
                if (state == Source.SENT) transactions.finalizeAccepted(id);
                if (state == Source.WITHDRAWN_ACCEPTED) {
                    jdbc.update("update reminders set withdrawn_at = ? where id = ?", now(), reminder.getId());
                }
                if (state == Source.RETRY_WITHOUT_TIME) jdbc.update("update deliveries set next_attempt_at = null where id = ?", id);
            }
        }
        return reminders.findById(reminder.getId()).orElseThrow();
    }

    static Stream<Arguments> states() {
        return Stream.of(
                Arguments.of(Source.READY, Feedback.WAITING, true, false),
                Arguments.of(Source.UNKNOWN, Feedback.DELIVERY_UNCERTAIN, false, false),
                Arguments.of(Source.ACCEPTED, Feedback.ACCEPTED, false, true),
                Arguments.of(Source.SENT, Feedback.ACCEPTED, false, true),
                Arguments.of(Source.WITHDRAWN, Feedback.WITHDRAWN, false, true),
                Arguments.of(Source.PERMANENT, Feedback.PERMANENT_REJECTION, false, true),
                Arguments.of(Source.RETRYABLE, Feedback.RETRY_WAITING, false, false),
                Arguments.of(Source.EXHAUSTED, Feedback.RETRY_EXHAUSTED, false, false),
                Arguments.of(Source.EXTRA, Feedback.RETRY_WAITING, false, false),
                Arguments.of(Source.LEGACY_SENT, Feedback.LEGACY_SENT, false, true),
                Arguments.of(Source.LEGACY_PENDING, Feedback.UNTRACKED, false, false),
                Arguments.of(Source.MISSING, Feedback.INCONSISTENT_DELIVERY, false, false),
                Arguments.of(Source.MISMATCH, Feedback.INCONSISTENT_DELIVERY, false, false),
                Arguments.of(Source.SENT_READY, Feedback.INCONSISTENT_DELIVERY, false, false),
                Arguments.of(Source.WITHDRAWN_ACCEPTED, Feedback.INCONSISTENT_DELIVERY, false, false),
                Arguments.of(Source.READY_CLAIMED, Feedback.INCONSISTENT_DELIVERY, false, false),
                Arguments.of(Source.RETRY_WITHOUT_TIME, Feedback.INCONSISTENT_DELIVERY, false, false));
    }

    @ParameterizedTest
    @MethodSource("states")
    void stateFeedbackAndAdvisoryActionsPreserveEvidence(Source state, Feedback feedback, boolean editable, boolean reusable) {
        Reminder source = source(state);
        var before = rows();
        var sourceBefore = sourceRows(source.getId());
        ReminderViewResponse result = detail(source.getId());
        assertThat(view("recent")).containsExactly(result);
        assertThat(result.feedback()).isEqualTo(feedback);
        assertThat(result.canEdit()).isEqualTo(editable);
        assertThat(result.canWithdraw()).isEqualTo(editable);
        assertThat(result.canRemindAgain()).isEqualTo(reusable);
        assertThat(result.message()).isEqualTo(source.getMessage());
        assertThat(result.phoneNumber()).isEqualTo(source.getPhoneNumber());
        assertThat(result.version()).isEqualTo(source.getVersion());
        if (feedback == Feedback.RETRY_WAITING) {
            assertThat(result.nextAttemptAt()).isEqualTo(deliveries.findById(source.getCurrentDeliveryId()).orElseThrow().getNextAttemptAt());
        } else {
            assertThat(result.nextAttemptAt()).isNull();
        }
        assertThat(rows()).isEqualTo(before);
        if (feedback != Feedback.INCONSISTENT_DELIVERY) {
            var response = reuse(source.getId(), reuseRequest(result));
            assertThat(response.getStatusCode()).isEqualTo(reusable ? HttpStatus.OK : HttpStatus.CONFLICT);
            if (reusable) {
                assertThat(view("upcoming")).extracting(ReminderViewResponse::id).containsExactly(response.getBody().path("id").asLong());
                assertThat(view("recent")).containsExactly(result);
            }
            if (!editable) {
                assertThat(edit(result).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(withdraw(result.id(), result.version()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            }
            assertThat(sourceRows(source.getId())).isEqualTo(sourceBefore);
            if (!reusable) assertThat(rows()).isEqualTo(before);
        }
        verifyNoInteractions(sender);
    }

    @Test
    void futureHeldAlertsRemainVisibleWithTheirFeedback() {
        Reminder uncertain = source(Source.UNKNOWN);
        Reminder failed = source(Source.PERMANENT);
        Reminder exhausted = source(Source.EXHAUSTED);
        for (Reminder reminder : List.of(uncertain, failed, exhausted)) {
            jdbc.update("update reminders set scheduled_at = ? where id = ?", now().plusDays(1), reminder.getId());
        }
        assertThat(view("upcoming")).extracting(ReminderViewResponse::feedback)
                .containsExactly(Feedback.DELIVERY_UNCERTAIN, Feedback.PERMANENT_REJECTION, Feedback.RETRY_EXHAUSTED);
        assertThat(view("recent")).isEmpty();
        verifyNoInteractions(sender);
    }

    @Test
    void repeatedReadsAndWindowExpiryNeverAdoptSendOrChangeAnyStoredData() {
        Reminder uncertain = source(Source.UNKNOWN);
        Reminder exhausted = source(Source.EXHAUSTED);
        Reminder accepted = source(Source.ACCEPTED);
        var request = reuseRequest(detail(accepted.getId()));
        assertThat(reuse(accepted.getId(), request).getStatusCode()).isEqualTo(HttpStatus.OK);
        Reminder untrackedPast = source(Source.LEGACY_PENDING);
        Reminder untrackedFuture = reminders.save(new Reminder("Untracked future", PHONE, now().plusDays(2)));
        var before = rows();
        for (int i = 0; i < 3; i++) {
            view("upcoming");
            view("recent");
            for (Reminder reminder : List.of(uncertain, exhausted, accepted, untrackedPast, untrackedFuture)) detail(reminder.getId());
            http.getForEntity(BASE, ReminderResponse[].class);
        }
        clock.advance(Duration.ofDays(10));
        assertThat(view("recent")).isEmpty();
        assertThat(view("upcoming")).isEmpty();
        assertThat(detail(uncertain.getId()).feedback()).isEqualTo(Feedback.DELIVERY_UNCERTAIN);
        assertThat(detail(untrackedPast.getId()).feedback()).isEqualTo(Feedback.UNTRACKED);
        assertThat(http.getForObject(BASE, ReminderResponse[].class)).hasSize(6);
        assertThat(rows()).isEqualTo(before);
        verifyNoInteractions(sender);
    }

    @Test
    void createEditWithdrawAndReuseUseCurrentReadVersionsAndPreserveSnapshots() {
        var created = http.postForEntity(BASE, new CreateReminderRequest("Initial message", now().plusDays(1), PHONE), ReminderResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long id = created.getBody().id();
        ReminderViewResponse initial = detail(id);
        assertThat(view("upcoming")).containsExactly(initial);
        var edited = edit(initial);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReminderViewResponse corrected = detail(id);
        assertThat(corrected.version()).isEqualTo(edited.getBody().path("version").asLong()).isGreaterThan(initial.version());
        assertThat(corrected.message()).isEqualTo("Corrected message");
        assertThat(view("upcoming")).containsExactly(corrected);
        assertThat(edit(initial).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(withdraw(id, corrected.version()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view("upcoming")).isEmpty();
        ReminderViewResponse withdrawn = detail(id);
        var original = sourceRows(id);
        var request = reuseRequest(withdrawn);
        var reused = reuse(id, request);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view("upcoming")).extracting(ReminderViewResponse::id).containsExactly(reused.getBody().path("id").asLong());
        assertThat(reuse(id, request).getBody()).isEqualTo(reused.getBody());
        assertThat(sourceRows(id)).isEqualTo(original);
        assertThat(deliveries.count()).isEqualTo(3);
        assertThat(jdbc.queryForList("select message from deliveries where reminder_id = ? order by created_at, message", String.class, id))
                .containsExactlyInAnyOrder("TextLoop Reminder:\nInitial message", "TextLoop Reminder:\nCorrected message");
        clock.advance(Duration.ofDays(3));
        assertThat(view("recent")).extracting(ReminderViewResponse::id).containsExactly(id, reused.getBody().path("id").asLong());
        assertThat(view("upcoming")).isEmpty();
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claim", "edit"})
    void reminderAndDeliveryReadsUseOneSnapshotDuringConcurrentCommit(String change) throws Exception {
        Reminder source = source(now().plusDays(1));
        ReminderViewResponse original = detail(source.getId());
        barrier.arm();
        var reading = CompletableFuture.supplyAsync(() -> view("upcoming"));
        try {
            assertThat(barrier.entered.await(10, TimeUnit.SECONDS)).isTrue();
            // The first SELECT completed. Commit a real change before allowing the delivery SELECT.
            if (change.equals("claim")) {
                assertThat(transactions.claim(source.getCurrentDeliveryId(), original.scheduledAt())).isPresent();
            } else {
                assertThat(edit(original).getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            barrier.release();
        }
        assertThat(reading.get(10, TimeUnit.SECONDS)).containsExactly(original);
        ReminderViewResponse refreshed = detail(source.getId());
        if (change.equals("claim")) {
            assertThat(refreshed.feedback()).isEqualTo(Feedback.DELIVERY_UNCERTAIN);
            assertThat(refreshed.canEdit()).isFalse();
            assertThat(refreshed.version()).isEqualTo(original.version());
            assertThat(edit(original).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(withdraw(original.id(), original.version()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        } else {
            assertThat(refreshed.message()).isEqualTo("Corrected message");
            assertThat(refreshed.version()).isGreaterThan(original.version());
        }
        verifyNoInteractions(sender);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary MutableClock viewClock() { return new MutableClock(); }
        @Bean ReadBarrier readBarrier() { return new ReadBarrier(); }
        @Bean HibernatePropertiesCustomizer readInspector(ReadBarrier barrier) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", barrier);
        }
    }

    static class MutableClock extends Clock {
        volatile Instant instant = Instant.parse("2030-01-01T12:00:00.123456789Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }

    static class ReadBarrier implements StatementInspector {
        private final AtomicBoolean armed = new AtomicBoolean();
        volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch proceed = new CountDownLatch(0);
        void arm() {
            entered = new CountDownLatch(1);
            proceed = new CountDownLatch(1);
            armed.set(true);
        }
        void release() { armed.set(false); proceed.countDown(); }
        @Override public String inspect(String sql) {
            if (sql.startsWith("select ") && sql.contains(" from deliveries ") && armed.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!proceed.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Read pause timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Read pause interrupted");
                }
            }
            return sql;
        }
    }
}
