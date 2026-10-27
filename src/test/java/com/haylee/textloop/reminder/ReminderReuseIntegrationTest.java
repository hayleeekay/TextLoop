package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
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
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;
import com.haylee.textloop.LoggingStateExtension;
import com.haylee.textloop.TextloopApplication;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.EditReminderRequest;
import com.haylee.textloop.reminder.dto.RemindAgainRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;
import com.haylee.textloop.sms.SmsService;
import com.haylee.textloop.sms.SmsSubmissionResult;

@ExtendWith({LoggingStateExtension.class, OutputCaptureExtension.class})
class ReminderReuseIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String MESSAGE = "private-message-marker";
    private static final String PHONE = "+12035550100";
    private static SmsService sender;
    private static MutableClock clock;
    private ConfigurableApplicationContext context;
    private final TestRestTemplate http = new TestRestTemplate();

    @BeforeAll
    static void startPostgres() { postgres.start(); }

    @AfterAll
    static void stopPostgres() { postgres.stop(); }

    @BeforeEach
    void startFresh() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("drop schema public cascade");
            statement.execute("create schema public");
        }
        clock = new MutableClock();
        sender = mock(SmsService.class);
        when(sender.sendSms(any(), anyString(), anyString())).thenReturn(SmsSubmissionResult.accepted(null));
        boot();
    }

    @AfterEach
    void close() { if (context != null) context.close(); }

    private void boot() {
        if (context != null) context.close();
        var application = TextloopApplication.application();
        application.setLogStartupInfo(false);
        application.addPrimarySources(List.of(Fixtures.class));
        context = application.run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.jpa.hibernate.ddl-auto=update", "--spring.jpa.show-sql=false",
                "--spring.main.web-application-type=servlet", "--server.port=0",
                "--textloop.scheduling.enabled=false", "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    private ReminderRepository reminders() { return context.getBean(ReminderRepository.class); }
    private DeliveryRepository deliveries() { return context.getBean(DeliveryRepository.class); }
    private DeliveryTransactions transactions() { return context.getBean(DeliveryTransactions.class); }
    private ReminderService service() { return context.getBean(ReminderService.class); }
    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private int port() { return ((WebServerApplicationContext) context).getWebServer().getPort(); }
    private String url(String path) { return "http://localhost:" + port() + path; }
    private String path(Long id) { return "/api/reminders/" + id + "/remind-again"; }
    private Reminder reload(Reminder source) { return reminders().findById(source.getId()).orElseThrow(); }
    private Delivery delivery(Reminder source) { return deliveries().findById(reload(source).getCurrentDeliveryId()).orElseThrow(); }
    private RemindAgainRequest request(Reminder source) {
        return new RemindAgainRequest(UUID.randomUUID(), source.getVersion(), now().plusDays(1));
    }
    private ResponseEntity<ReminderResponse> save(Reminder source, RemindAgainRequest request) {
        return http.postForEntity(url(path(source.getId())), request, ReminderResponse.class);
    }
    private ResponseEntity<String> raw(Long sourceId, Object request) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url(path(sourceId)), HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
    }
    private List<?> rows() {
        return List.of(jdbc().queryForList("select * from reminders order by id"),
                jdbc().queryForList("select * from deliveries order by id"),
                jdbc().queryForList("select * from reminder_reuses order by id"));
    }
    private List<?> sourceRows(Reminder source) {
        return List.of(jdbc().queryForList("select * from reminders where id = ?", source.getId()),
                jdbc().queryForList("select * from deliveries where reminder_id = ? order by id", source.getId()));
    }
    private void assertCounts(int reminders, int deliveries, int operations) {
        assertThat(reminders().count()).isEqualTo(reminders);
        assertThat(deliveries().count()).isEqualTo(deliveries);
        assertThat(jdbc().queryForObject("select count(*) from reminder_reuses", Integer.class)).isEqualTo(operations);
    }
    private void assertError(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo("{\"code\":\"" + code + "\"}");
    }

    enum Source { READY, UNKNOWN, ACCEPTED, SENT, WITHDRAWN, PERMANENT, RETRYABLE, EXHAUSTED, EXTRA, LEGACY_SENT }

    private Reminder source(Source state) {
        if (state == Source.LEGACY_SENT) {
            var legacy = new Reminder(MESSAGE, PHONE, now().minusDays(1));
            legacy.setStatus(ReminderStatus.SENT);
            return reminders().save(legacy);
        }
        LocalDateTime due = now().minusMinutes(1).truncatedTo(ChronoUnit.MICROS);
        var present = clock.instant;
        clock.instant = present.minus(Duration.ofMinutes(2));
        ReminderResponse created;
        try {
            created = service().createReminder(new CreateReminderRequest(MESSAGE, due, PHONE));
        } finally {
            clock.instant = present;
        }
        var source = reminders().findById(created.id()).orElseThrow();
        var id = source.getCurrentDeliveryId();
        if (state == Source.WITHDRAWN) {
            transactions().withdrawReminder(source.getId(), source.getVersion());
        } else if (state != Source.READY) {
            var attempt = transactions().claim(id, now()).orElseThrow();
            if (state != Source.UNKNOWN) {
                var outcome = switch (state) {
                    case PERMANENT -> SmsSubmissionResult.permanentRejection();
                    case RETRYABLE, EXHAUSTED, EXTRA -> SmsSubmissionResult.retryableRejection();
                    default -> SmsSubmissionResult.accepted("accepted-reference");
                };
                transactions().recordResult(id, attempt.attemptCount(), outcome);
            }
            if (state == Source.EXHAUSTED || state == Source.EXTRA) {
                for (int i = 0; i < 2; i++) {
                    clock.advance(Duration.ofMinutes(6));
                    attempt = transactions().claim(id, now()).orElseThrow();
                    transactions().recordResult(id, attempt.attemptCount(), SmsSubmissionResult.retryableRejection());
                }
                if (state == Source.EXTRA) {
                    transactions().recover(id, delivery(source).getVersion(), RecoveryAction.RETRY_ONCE, false);
                }
            }
            if (state == Source.SENT) transactions().finalizeAccepted(id);
        }
        return reload(source);
    }

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"SENT", "ACCEPTED", "WITHDRAWN", "PERMANENT", "LEGACY_SENT"})
    void createsFreshAlertAndPreservesSource(Source state) {
        Reminder source = source(state);
        if (state == Source.SENT) {
            jdbc().update("update deliveries set message = ? where id = ?",
                    "TextLoop Reminder:\n" + MESSAGE + "\nReply DONE, SNOOZE, or CANCEL.", source.getCurrentDeliveryId());
        }
        var before = sourceRows(source);
        var request = request(source);
        var response = save(source, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var result = response.getBody();
        assertThat(result.id()).isNotEqualTo(source.getId());
        assertThat(result.message()).isEqualTo(MESSAGE);
        assertThat(result.phoneNumber()).isEqualTo(PHONE);
        assertThat(result.scheduledAt()).isEqualTo(request.scheduledAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(result.status()).isEqualTo(ReminderStatus.PENDING);
        assertThat(result.withdrawnAt()).isNull();
        var saved = reminders().findById(result.id()).orElseThrow();
        assertThat(result.version()).isEqualTo(saved.getVersion());
        assertThat(result.scheduledAt()).isEqualTo(saved.getScheduledAt());
        var fresh = delivery(saved);
        assertThat(fresh.getState()).isEqualTo(DeliveryState.READY);
        assertThat(fresh.getAttemptCount()).isZero();
        assertThat(fresh.getMessage()).isEqualTo("TextLoop Reminder:\n" + MESSAGE);
        assertThat(fresh.getReplacesDeliveryId()).isNull();
        assertThat(sourceRows(source)).isEqualTo(before);
        assertCounts(2, state == Source.LEGACY_SENT ? 1 : 2, 1);
        http.getForEntity(url("/api/reminders"), String.class);
        assertThat(save(source, request).getBody()).isEqualTo(result);
        verifyNoInteractions(sender);
    }

    @Test
    void legacySourcePayloadIsCopiedWithoutApplyingNewRequestConstraints() {
        var source = source(Source.LEGACY_SENT);
        String legacyPhone = "😀".repeat(200);
        jdbc().update("update reminders set message = ?, phone_number = ? where id = ?", "  ", legacyPhone, source.getId());
        var before = sourceRows(source);
        var request = request(source);
        var result = save(source, request).getBody();
        assertThat(result.message()).isEqualTo("  ");
        assertThat(result.phoneNumber()).isEqualTo(legacyPhone);
        assertThat(sourceRows(source)).isEqualTo(before);
        clock.advance(Duration.ofDays(2));
        boot();
        before = rows();
        assertThat(save(source, request).getBody()).isEqualTo(result);
        assertThat(rows()).isEqualTo(before);
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"READY", "UNKNOWN", "RETRYABLE", "EXHAUSTED", "EXTRA"})
    void blocksSourcesWhoseDeliveryIsActiveUncertainOrRecoverable(Source state) {
        var source = source(state);
        var before = rows();
        assertError(raw(source.getId(), request(source)), HttpStatus.CONFLICT,
                state == Source.UNKNOWN ? "DELIVERY_REVIEW_REQUIRED" : "NOT_REUSABLE");
        assertThat(rows()).isEqualTo(before);
        verifyNoInteractions(sender);
    }

    @Test
    void refusedUnknownSourceRemainsHeldAfterRestartAndSchedulerPass() {
        var source = source(Source.UNKNOWN);
        var before = rows();
        assertError(raw(source.getId(), request(source)), HttpStatus.CONFLICT, "DELIVERY_REVIEW_REQUIRED");
        boot();
        clock.advance(Duration.ofDays(2));
        service().processDueReminders(now());
        assertThat(rows()).isEqualTo(before);
        verifyNoInteractions(sender);
    }

    @Test
    void missingUntrackedAndInconsistentSourcesCannotCreateAlerts() {
        var source = source(Source.SENT);
        var request = request(source);
        assertError(raw(Long.MAX_VALUE, request), HttpStatus.NOT_FOUND, "NOT_FOUND");
        jdbc().update("update reminders set status = 'PENDING', current_delivery_id = null where id = ?", source.getId());
        var before = rows();
        assertError(raw(source.getId(), request), HttpStatus.CONFLICT, "NOT_REUSABLE");
        assertThat(rows()).isEqualTo(before);
        jdbc().update("update reminders set current_delivery_id = ? where id = ?", UUID.randomUUID(), source.getId());
        before = rows();
        assertError(raw(source.getId(), request), HttpStatus.CONFLICT, "NOT_REUSABLE");
        assertThat(rows()).isEqualTo(before);
        var other = source(Source.SENT);
        jdbc().update("update reminders set current_delivery_id = ? where id = ?", other.getCurrentDeliveryId(), source.getId());
        before = rows();
        assertError(raw(source.getId(), request), HttpStatus.CONFLICT, "NOT_REUSABLE");
        assertThat(rows()).isEqualTo(before);
        jdbc().update("update reminders set current_delivery_id = ?, status = 'SENT' where id = ?",
                source.getCurrentDeliveryId(), source.getId());
        jdbc().update("update deliveries set state = 'READY' where id = ?", source.getCurrentDeliveryId());
        before = rows();
        assertError(raw(source.getId(), request), HttpStatus.CONFLICT, "NOT_REUSABLE");
        assertThat(rows()).isEqualTo(before);
        verifyNoInteractions(sender);
    }

    @Test
    void bindingRejectsDifferentInputsButSeparateKeysAndSourcesRemainPossible() {
        var source = source(Source.SENT);
        var request = request(source);
        var result = save(source, request).getBody();
        var before = rows();
        String withSuffix = context.getBean(ObjectMapper.class).writeValueAsString(request)
                .replace(request.scheduledAt().toString(), request.scheduledAt() + "Z");
        assertThat(raw(source.getId(), withSuffix).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertError(raw(source.getId(), new RemindAgainRequest(request.operationId(), request.expectedVersion() + 1,
                request.scheduledAt())), HttpStatus.CONFLICT, "OPERATION_CONFLICT");
        assertError(raw(source.getId(), new RemindAgainRequest(request.operationId(), request.expectedVersion(),
                request.scheduledAt().plusNanos(1000))), HttpStatus.CONFLICT, "OPERATION_CONFLICT");
        assertThat(rows()).isEqualTo(before);
        assertThat(save(source, new RemindAgainRequest(request.operationId(), request.expectedVersion(),
                request.scheduledAt().minusNanos(1))).getBody()).isEqualTo(result);
        var separate = save(source, new RemindAgainRequest(UUID.randomUUID(), request.expectedVersion(), request.scheduledAt()));
        assertThat(separate.getBody().id()).isNotEqualTo(result.id());
        var other = source(Source.SENT);
        assertThat(save(other, new RemindAgainRequest(request.operationId(), other.getVersion(), request.scheduledAt()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCounts(5, 5, 3);
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentSavesHaveOneBinding(boolean conflicting) throws Exception {
        var source = source(Source.SENT);
        var request = request(source);
        var second = conflicting ? new RemindAgainRequest(request.operationId(), request.expectedVersion(),
                request.scheduledAt().plusHours(1)) : request;
        var start = new CountDownLatch(1);
        var firstResponse = CompletableFuture.supplyAsync(() -> afterStart(start, () -> raw(source.getId(), request)));
        var secondResponse = CompletableFuture.supplyAsync(() -> afterStart(start, () -> raw(source.getId(), second)));
        start.countDown();
        var responses = List.of(firstResponse.get(10, TimeUnit.SECONDS), secondResponse.get(10, TimeUnit.SECONDS));
        assertThat(responses.stream().map(ResponseEntity::getStatusCode)).containsExactlyInAnyOrder(
                HttpStatus.OK, conflicting ? HttpStatus.CONFLICT : HttpStatus.OK);
        if (conflicting) {
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow().getBody())
                    .isEqualTo("{\"code\":\"OPERATION_CONFLICT\"}");
        } else {
            assertThat(responses.get(0).getBody()).isEqualTo(responses.get(1).getBody());
        }
        assertCounts(2, 2, 1);
        verifyNoInteractions(sender);
    }

    @Test
    void concurrentSeparateOperationsCanIntentionallyScheduleTheSameTime() throws Exception {
        var source = source(Source.SENT);
        var request = request(source);
        var other = new RemindAgainRequest(UUID.randomUUID(), request.expectedVersion(), request.scheduledAt());
        var start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> afterStart(start, () -> save(source, request)));
        var second = CompletableFuture.supplyAsync(() -> afterStart(start, () -> save(source, other)));
        start.countDown();
        var a = first.get(10, TimeUnit.SECONDS);
        var b = second.get(10, TimeUnit.SECONDS);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.getBody().id()).isNotEqualTo(b.getBody().id());
        assertCounts(3, 3, 2);
        verifyNoInteractions(sender);
    }

    @Test
    void databaseConstraintEnforcesOneBindingPerSourceAndOperation() {
        var source = source(Source.SENT);
        save(source, request(source));
        assertThatThrownBy(() -> jdbc().update("""
                insert into reminder_reuses (source_reminder_id, operation_id, expected_version, scheduled_at, result_reminder_id)
                select source_reminder_id, operation_id, expected_version, scheduled_at, result_reminder_id from reminder_reuses
                """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertCounts(2, 2, 1);
    }

    private <T> T afterStart(CountDownLatch start, Supplier<T> action) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
            return action.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "withdraw", "send"})
    void replayReturnsCurrentResultAfterChangesAndRestart(String action) {
        var source = source(Source.ACCEPTED);
        var request = request(source);
        var result = save(source, request).getBody();
        transactions().finalizeAccepted(source.getCurrentDeliveryId());
        switch (action) {
            case "edit" -> service().editReminder(result.id(), new EditReminderRequest("Changed result",
                    request.scheduledAt().plusDays(1), result.version()));
            case "withdraw" -> service().withdrawReminder(result.id(), result.version());
            case "send" -> {
                clock.advance(Duration.ofDays(2));
                service().processDueReminders(now());
                verify(sender, times(1)).sendSms(any(), anyString(), anyString());
            }
            default -> throw new IllegalArgumentException(action);
        }
        var expected = service().getReminders().stream().filter(r -> r.id().equals(result.id())).findFirst().orElseThrow();
        clock.advance(Duration.ofDays(3));
        boot();
        clearInvocations(sender);
        var before = rows();
        var equivalent = new RemindAgainRequest(request.operationId(), request.expectedVersion(),
                request.scheduledAt().plusNanos(1));
        assertThat(save(source, equivalent).getBody()).isEqualTo(expected);
        assertThat(rows()).isEqualTo(before);
        assertError(raw(source.getId(), new RemindAgainRequest(UUID.randomUUID(), request.expectedVersion(),
                now().plusDays(1))), HttpStatus.CONFLICT, "STALE_VERSION");
        verifyNoInteractions(sender);
    }

    @Test
    void lostResponseStillReplaysTheCommittedOperationAfterRestart() throws Exception {
        var source = source(Source.SENT);
        var request = request(source);
        byte[] body = context.getBean(ObjectMapper.class).writeValueAsBytes(request);
        try (var socket = new Socket("localhost", port())) {
            var out = socket.getOutputStream();
            out.write(("POST " + path(source.getId()) + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            // Discard the connection without reading the save response.
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertCounts(2, 2, 1));
        Long resultId = jdbc().queryForObject("select result_reminder_id from reminder_reuses", Long.class);
        boot();
        assertThat(save(source, request).getBody().id()).isEqualTo(resultId);
        assertCounts(2, 2, 1);
        verifyNoInteractions(sender);
    }

    static Stream<String> invalidRequests() {
        String key = "\"operationId\":\"2af941dd-3fb2-4f75-a673-50b398ca468c\"";
        String version = "\"expectedVersion\":0";
        String time = "\"scheduledAt\":\"2030-01-02T12:00:00\"";
        return Stream.of("{}", "null", "{", "[]", "{" + version + "," + time + "}",
                "{" + key + "," + time + "}", "{" + key + "," + version + "}",
                "{\"operationId\":null," + version + "," + time + "}",
                "{" + key + ",\"expectedVersion\":null," + time + "}",
                "{" + key + "," + version + ",\"scheduledAt\":null}",
                "{\"operationId\":\"private-key-marker\"," + version + "," + time + "}",
                "{" + key + "," + version + ",\"scheduledAt\":\"private-time-marker\"}",
                "{" + key + ",\"expectedVersion\":-1," + time + "}",
                "{" + key + ",\"expectedVersion\":0.9," + time + "}",
                "{" + key + ",\"expectedVersion\":-0.9," + time + "}");
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidBodiesSaveNothingAndDoNotExposeInput(String body, CapturedOutput output) {
        var source = source(Source.LEGACY_SENT);
        var before = rows();
        var response = raw(source.getId(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContain(MESSAGE, PHONE, "private-key-marker", "private-time-marker");
        assertThat(output).doesNotContain(MESSAGE, PHONE, "private-key-marker", "private-time-marker");
        assertThat(rows()).isEqualTo(before);
        verifyNoInteractions(sender);
    }

    @Test
    void malformedPathAndNewPastOrStaleRequestsHaveFixedErrors(CapturedOutput output) {
        var source = source(Source.SENT);
        var request = request(source);
        var before = rows();
        var response = http.postForEntity(url("/api/reminders/private-id-marker/remind-again"), request, String.class);
        assertError(response, HttpStatus.BAD_REQUEST, "INVALID_ID");
        assertThat(output).doesNotContain("private-id-marker");
        for (var time : List.of(now(), now().minusDays(1), now().plusNanos(1))) {
            assertError(raw(source.getId(), new RemindAgainRequest(request.operationId(), request.expectedVersion(), time)),
                    HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE");
        }
        assertError(raw(source.getId(), new RemindAgainRequest(request.operationId(), request.expectedVersion() + 1,
                request.scheduledAt())), HttpStatus.CONFLICT, "STALE_VERSION");
        assertThat(rows()).isEqualTo(before);
        assertThat(save(source, request).getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(sender);
    }

    @Test
    void newTimeIsValidatedAfterWaitingForSourceLock() throws Exception {
        var source = source(Source.SENT);
        var request = new RemindAgainRequest(UUID.randomUUID(), source.getVersion(), now().plusMinutes(1));
        var before = rows();
        var pending = transaction().execute(status -> {
            reminders().findLockedById(source.getId()).orElseThrow();
            var response = CompletableFuture.supplyAsync(() -> raw(source.getId(), request));
            awaitLocks(1);
            clock.advance(Duration.ofMinutes(1));
            return response;
        });
        assertError(pending.get(10, TimeUnit.SECONDS), HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE");
        assertThat(rows()).isEqualTo(before);
        verifyNoInteractions(sender);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    private void awaitLocks(int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> jdbc().queryForObject(
                "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class) >= count);
    }

    // Hold only the delivery in the fixture so the real first transaction owns the source lock.
    private <T, U> List<Object> ordered(Reminder source, Supplier<T> first, Supplier<U> second) throws Exception {
        var pending = transaction().execute(status -> {
            deliveries().findLockedById(source.getCurrentDeliveryId()).orElseThrow();
            var a = CompletableFuture.supplyAsync(first);
            awaitLocks(1);
            var b = CompletableFuture.supplyAsync(second);
            awaitLocks(2);
            return List.of(a, b);
        });
        return List.of(pending.get(0).get(10, TimeUnit.SECONDS), pending.get(1).get(10, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @CsvSource({"claim,true", "claim,false", "edit,true", "edit,false", "withdraw,true", "withdraw,false"})
    void claimAndCorrectionsHaveExplicitOutcomesInEitherLockOrder(String action, boolean mutationFirst) throws Exception {
        var source = source(Source.READY);
        var request = request(source);
        Supplier<Object> mutation = () -> switch (action) {
            case "claim" -> transactions().claim(source.getCurrentDeliveryId(), now());
            case "edit" -> service().editReminder(source.getId(), new EditReminderRequest("Corrected",
                    now().plusDays(2), source.getVersion()));
            case "withdraw" -> service().withdrawReminder(source.getId(), source.getVersion());
            default -> throw new IllegalArgumentException(action);
        };
        Supplier<?> reuse = () -> raw(source.getId(), request);
        var results = mutationFirst ? ordered(source, mutation, reuse) : ordered(source, reuse, mutation);
        @SuppressWarnings("unchecked") var response = (ResponseEntity<String>) results.get(mutationFirst ? 1 : 0);
        assertError(response, HttpStatus.CONFLICT, mutationFirst
                ? (action.equals("claim") ? "DELIVERY_REVIEW_REQUIRED" : "STALE_VERSION") : "NOT_REUSABLE");
        if (action.equals("claim")) {
            transactions().recordResult(source.getCurrentDeliveryId(), 1, SmsSubmissionResult.accepted(null));
        } else {
            assertThat(transactions().claim(source.getCurrentDeliveryId(), now())).isEmpty();
            var refreshed = reload(source);
            assertThat(raw(refreshed.getId(), request(refreshed)).getStatusCode())
                    .isEqualTo(action.equals("withdraw") ? HttpStatus.OK : HttpStatus.CONFLICT);
        }
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptanceFinalizationAndReuseSerializeWithoutResubmission(boolean finalizeFirst) throws Exception {
        var source = source(Source.ACCEPTED);
        var request = request(source);
        Supplier<?> finalize = () -> transactions().finalizeAccepted(source.getCurrentDeliveryId());
        Supplier<?> reuse = () -> raw(source.getId(), request);
        var results = finalizeFirst ? ordered(source, finalize, reuse) : ordered(source, reuse, finalize);
        @SuppressWarnings("unchecked") var response = (ResponseEntity<String>) results.get(finalizeFirst ? 1 : 0);
        if (finalizeFirst) {
            assertError(response, HttpStatus.CONFLICT, "STALE_VERSION");
        } else {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(raw(source.getId(), request).getBody()).isEqualTo(response.getBody());
        }
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownReviewAndReuseSerializeWithoutBypassingTheHold(boolean reviewFirst) throws Exception {
        var source = source(Source.UNKNOWN);
        var request = request(source);
        long version = delivery(source).getVersion();
        Supplier<?> review = () -> transactions().recover(source.getCurrentDeliveryId(), version,
                RecoveryAction.CONFIRM_ACCEPTED, false);
        Supplier<?> reuse = () -> raw(source.getId(), request);
        var results = reviewFirst ? ordered(source, review, reuse) : ordered(source, reuse, review);
        @SuppressWarnings("unchecked") var response = (ResponseEntity<String>) results.get(reviewFirst ? 1 : 0);
        if (reviewFirst) assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        else assertError(response, HttpStatus.CONFLICT, "DELIVERY_REVIEW_REQUIRED");
        assertCounts(reviewFirst ? 2 : 1, reviewFirst ? 2 : 1, reviewFirst ? 1 : 0);
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reminders", "deliveries", "reminder_reuses"})
    void httpCommitFailureRollsBackEverythingAndOmitsDatabasePayload(String table, CapturedOutput output) {
        var source = source(Source.SENT);
        var request = request(source);
        var before = rows();
        jdbc().execute("create function fail_reuse_commit() returns trigger language plpgsql as $$ begin "
                + "raise exception 'private-database-marker % %', '" + MESSAGE + "', '" + PHONE + "'; end $$");
        jdbc().execute("create constraint trigger failure_at_commit after insert on " + table
                + " deferrable initially deferred for each row execute function fail_reuse_commit()");
        assertError(raw(source.getId(), request), HttpStatus.SERVICE_UNAVAILABLE, "SAVE_FAILED");
        assertThat(rows()).isEqualTo(before);
        assertThat(output).doesNotContain(MESSAGE, PHONE, "private-database-marker");
        jdbc().execute("drop trigger failure_at_commit on " + table);
        jdbc().execute("drop function fail_reuse_commit()");
        boot();
        assertThat(save(source, request).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCounts(2, 2, 1);
        verifyNoInteractions(sender);
    }

    @Test
    void populatedSchemaUpgradeIsAdditiveAndBindingSurvivesRepeatedStartup() {
        var source = source(Source.SENT);
        var before = sourceRows(source);
        context.close();
        context = null;
        var independent = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        independent.execute("drop table reminder_reuses");
        boot();
        assertThat(sourceRows(source)).isEqualTo(before);
        var request = request(source);
        var result = save(source, request).getBody();
        var saved = rows();
        boot();
        assertThat(rows()).isEqualTo(saved);
        assertThat(save(source, request).getBody()).isEqualTo(result);
        verifyNoInteractions(sender);
    }

    @Test
    void reusedWithdrawnCorrectionSendsOnlyTheNewAlertAtItsNewDeadline() {
        var source = source(Source.READY);
        var obsoleteId = source.getCurrentDeliveryId();
        var obsolete = delivery(source).getMessage();
        var edited = service().editReminder(source.getId(), new EditReminderRequest("Corrected source",
                now().plusHours(1), source.getVersion()));
        service().withdrawReminder(source.getId(), edited.version());
        source = reload(source);
        var request = new RemindAgainRequest(UUID.randomUUID(), source.getVersion(), now().plusHours(2));
        var result = save(source, request).getBody();
        var resultDelivery = reminders().findById(result.id()).orElseThrow().getCurrentDeliveryId();
        boot();
        clock.advance(Duration.ofHours(1));
        service().processDueReminders(now());
        verifyNoInteractions(sender);
        assertThat(deliveries().findById(obsoleteId).orElseThrow().getMessage()).isEqualTo(obsolete);
        clock.advance(Duration.ofHours(1));
        service().processDueReminders(now());
        service().processDueReminders(now());
        verify(sender, times(1)).sendSms(resultDelivery, PHONE, "TextLoop Reminder:\nCorrected source");
        verifyNoMoreInteractions(sender);
        assertThat(save(source, request).getBody().status()).isEqualTo(ReminderStatus.SENT);
        verifyNoMoreInteractions(sender);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary Clock reuseClock() { return clock; }
        @Bean @Primary SmsService reuseSender() { return sender; }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2030-01-01T12:00:00.123456789Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
