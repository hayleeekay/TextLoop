package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haylee.textloop.LoggingStateExtension;
import com.haylee.textloop.TextloopApplication;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.EditReminderRequest;
import com.haylee.textloop.reminder.dto.RemindAgainRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;
import com.haylee.textloop.reminder.dto.WithdrawReminderRequest;
import com.haylee.textloop.sms.SmsService;

@ExtendWith({LoggingStateExtension.class, OutputCaptureExtension.class})
class ReminderPersistenceFailureIntegrationTest {
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String MESSAGE = "private-message-marker";
    private static final String EDITED = "private-edited-message-marker";
    private static final String PHONE = "+12035550100";
    private static final String DATABASE_DETAIL = "private-database-marker";
    private static final String CAUSE_DETAIL = "private-cause-marker";
    private static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(2).withNano(0);
    private static ConfigurableApplicationContext context;
    private static SmsService sender;
    private final TestRestTemplate http = new TestRestTemplate();

    @BeforeAll
    static void start() {
        postgres.start();
        sender = mock(SmsService.class);
        var application = TextloopApplication.application();
        application.setLogStartupInfo(false);
        application.addPrimarySources(List.of(Fixtures.class));
        context = application.run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.jpa.hibernate.ddl-auto=create-drop", "--spring.jpa.show-sql=false",
                "--spring.main.web-application-type=servlet", "--server.port=0",
                "--textloop.scheduling.enabled=false", "--spring.main.banner-mode=off");
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        postgres.stop();
    }

    @BeforeEach
    void clearRows() {
        jdbc().execute("truncate table reminder_reuses, deliveries, reminders restart identity cascade");
    }

    @AfterEach
    void removeFaults() {
        faults().disarm();
        jdbc().execute("drop function if exists reject_mutation() cascade");
        verifyNoInteractions(sender);
    }

    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }
    private ReminderRepository reminders() { return context.getBean(ReminderRepository.class); }
    private DeliveryRepository deliveries() { return context.getBean(DeliveryRepository.class); }
    private ReminderService service() { return context.getBean(ReminderService.class); }
    private CommitFaultDataSource faults() { return context.getBean(CommitFaultDataSource.class); }
    private String url(String path) {
        return "http://localhost:" + ((WebServerApplicationContext) context).getWebServer().getPort() + path;
    }
    private List<List<Map<String, Object>>> rows() {
        return List.of(jdbc().queryForList("select * from reminders order by id"),
                jdbc().queryForList("select * from deliveries order by id"),
                jdbc().queryForList("select * from reminder_reuses order by id"));
    }

    enum Mutation { CREATE, EDIT, WITHDRAW, REUSE }

    private Reminder source(Mutation mutation) {
        if (mutation == Mutation.CREATE) return null;
        var created = service().createReminder(new CreateReminderRequest(MESSAGE, FUTURE, PHONE));
        if (mutation == Mutation.REUSE) service().withdrawReminder(created.id(), created.version());
        return reminders().findById(created.id()).orElseThrow();
    }

    private ResponseEntity<String> mutate(Mutation mutation, Reminder source, UUID operationId) {
        return switch (mutation) {
            case CREATE -> http.postForEntity(url("/api/reminders"),
                    new CreateReminderRequest(MESSAGE, FUTURE, PHONE), String.class);
            case EDIT -> http.exchange(url("/api/reminders/" + source.getId()), HttpMethod.PUT,
                    new HttpEntity<>(new EditReminderRequest(EDITED, FUTURE.plusDays(1), source.getVersion())), String.class);
            case WITHDRAW -> http.postForEntity(url("/api/reminders/" + source.getId() + "/withdraw"),
                    new WithdrawReminderRequest(source.getVersion()), String.class);
            case REUSE -> http.postForEntity(url("/api/reminders/" + source.getId() + "/remind-again"),
                    new RemindAgainRequest(operationId, source.getVersion(), FUTURE.plusDays(1)), String.class);
        };
    }

    private void assertSafeFailure(ResponseEntity<String> response, CapturedOutput output) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo("{\"code\":\"SAVE_FAILED\"}")
                .doesNotContain(MESSAGE, EDITED, PHONE, DATABASE_DETAIL, CAUSE_DETAIL);
        assertThat(output.getAll()).doesNotContain(MESSAGE, EDITED, PHONE, DATABASE_DETAIL, CAUSE_DETAIL);
    }

    static Stream<Arguments> rejectedWrites() {
        return Stream.of(
                new Object[] {Mutation.CREATE, "reminders", "insert"},
                new Object[] {Mutation.CREATE, "deliveries", "insert"},
                new Object[] {Mutation.EDIT, "deliveries", "insert"},
                new Object[] {Mutation.EDIT, "reminders", "update"},
                new Object[] {Mutation.WITHDRAW, "reminders", "update"})
                .flatMap(write -> Stream.of(false, true)
                        .map(deferred -> Arguments.of(write[0], write[1], write[2], deferred)));
    }

    @ParameterizedTest
    @MethodSource("rejectedWrites")
    void databaseRejectionRollsBackAllRowsWithoutExposingPayload(
            Mutation mutation, String table, String event, boolean deferred, CapturedOutput output) {
        Reminder source = source(mutation);
        var before = rows();
        jdbc().execute("create function reject_mutation() returns trigger language plpgsql as $$ begin "
                + "raise exception '" + DATABASE_DETAIL + " % %', new.message, new.phone_number; end $$");
        jdbc().execute("create " + (deferred ? "constraint " : "") + "trigger reject_write "
                + (deferred ? "after " : "before ") + event + " on " + table
                + (deferred ? " deferrable initially deferred" : "")
                + " for each row execute function reject_mutation()");

        assertSafeFailure(mutate(mutation, source, null), output);
        assertThat(rows()).isEqualTo(before);
        jdbc().execute("drop function reject_mutation() cascade");
        assertThat(mutate(mutation, source, null).getStatusCode())
                .isEqualTo(mutation == Mutation.CREATE ? HttpStatus.CREATED : HttpStatus.OK);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void lostCommitAcknowledgementReturnsSafeFailureButKeepsTheWholeMutation(
            Mutation mutation, CapturedOutput output) throws Exception {
        Reminder source = source(mutation);
        UUID operationId = UUID.randomUUID();
        var before = rows();
        faults().arm();
        ResponseEntity<String> response;
        try {
            response = mutate(mutation, source, operationId);
        } finally {
            faults().disarm();
        }
        assertThat(faults().commitCalls.get()).isEqualTo(1);
        assertThat(faults().injectedFailures.get()).isEqualTo(1);
        assertSafeFailure(response, output);
        var after = rows();

        if (mutation == Mutation.CREATE || mutation == Mutation.REUSE) {
            assertThat(after.get(0)).hasSize(mutation == Mutation.CREATE ? 1 : 2);
            assertThat(after.get(1)).hasSize(mutation == Mutation.CREATE ? 1 : 2);
            assertThat(after.get(2)).hasSize(mutation == Mutation.CREATE ? 0 : 1);
            var saved = reminders().findAll().stream().filter(row -> source == null || !row.getId().equals(source.getId()))
                    .findFirst().orElseThrow();
            assertThat(saved.getMessage()).isEqualTo(MESSAGE);
            assertThat(saved.getPhoneNumber()).isEqualTo(PHONE);
            assertThat(saved.getScheduledAt()).isEqualTo(mutation == Mutation.CREATE ? FUTURE : FUTURE.plusDays(1));
            assertThat(saved.getStatus()).isEqualTo(ReminderStatus.PENDING);
            assertThat(saved.getWithdrawnAt()).isNull();
            assertReady(saved);
            assertThat(deliveries().findById(saved.getCurrentDeliveryId()).orElseThrow().getReplacesDeliveryId()).isNull();
            if (mutation == Mutation.REUSE) {
                assertThat(after.get(0).getFirst()).isEqualTo(before.get(0).getFirst());
                assertThat(after.get(1)).containsAll(before.get(1));
                assertThat(after.get(2).getFirst())
                        .containsEntry("source_reminder_id", source.getId())
                        .containsEntry("result_reminder_id", saved.getId())
                        .containsEntry("operation_id", operationId)
                        .containsEntry("expected_version", source.getVersion())
                        .containsEntry("scheduled_at", java.sql.Timestamp.valueOf(FUTURE.plusDays(1)));
                var replay = mutate(mutation, source, operationId);
                assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(context.getBean(ObjectMapper.class).readValue(replay.getBody(), ReminderResponse.class).id())
                        .isEqualTo(saved.getId());
                assertThat(rows()).isEqualTo(after);
            }
        } else {
            assertThat(after.get(0)).hasSize(1);
            assertThat(after.get(2)).isEmpty();
            var saved = reminders().findById(source.getId()).orElseThrow();
            assertThat(saved.getVersion()).isEqualTo(source.getVersion() + 1);
            assertThat(saved.getPhoneNumber()).isEqualTo(PHONE);
            assertThat(saved.getStatus()).isEqualTo(ReminderStatus.PENDING);
            if (mutation == Mutation.EDIT) {
                assertThat(saved.getMessage()).isEqualTo(EDITED);
                assertThat(saved.getScheduledAt()).isEqualTo(FUTURE.plusDays(1));
                assertThat(saved.getWithdrawnAt()).isNull();
                assertThat(saved.getCurrentDeliveryId()).isNotEqualTo(source.getCurrentDeliveryId());
                assertThat(after.get(1)).hasSize(2).containsAll(before.get(1));
                assertReady(saved);
                assertThat(deliveries().findById(saved.getCurrentDeliveryId()).orElseThrow().getReplacesDeliveryId())
                        .isEqualTo(source.getCurrentDeliveryId());
                assertUnchangedColumns(before.get(0).getFirst(), after.get(0).getFirst(),
                        "message", "scheduled_at", "current_delivery_id", "version", "updated_at");
            } else {
                assertThat(saved.getMessage()).isEqualTo(MESSAGE);
                assertThat(saved.getScheduledAt()).isEqualTo(FUTURE);
                assertThat(saved.getCurrentDeliveryId()).isEqualTo(source.getCurrentDeliveryId());
                assertThat(saved.getWithdrawnAt()).isNotNull();
                assertThat(after.get(1)).isEqualTo(before.get(1));
                assertUnchangedColumns(before.get(0).getFirst(), after.get(0).getFirst(),
                        "withdrawn_at", "version", "updated_at");
            }
            assertThat(mutate(mutation, source, null).getBody()).isEqualTo("{\"code\":\"STALE_VERSION\"}");
            assertThat(rows()).isEqualTo(after);
        }
        assertThat(output.getAll()).doesNotContain(MESSAGE, EDITED, PHONE, DATABASE_DETAIL, CAUSE_DETAIL);
    }

    private void assertReady(Reminder reminder) {
        var delivery = deliveries().findById(reminder.getCurrentDeliveryId()).orElseThrow();
        assertThat(delivery.getReminder().getId()).isEqualTo(reminder.getId());
        assertThat(delivery.getMessage()).isEqualTo(Delivery.renderMessage(reminder.getMessage()));
        assertThat(delivery.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(delivery.getState()).isEqualTo(DeliveryState.READY);
        assertThat(delivery.getReason()).isEqualTo(DeliveryReason.NOT_SUBMITTED);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.isExtraAttemptPermitted()).isFalse();
        assertThat(delivery.getAcceptanceReference()).isNull();
        assertThat(delivery.getRecoveryAction()).isNull();
        assertThat(delivery.getRecoveredAt()).isNull();
    }

    private void assertUnchangedColumns(Map<String, Object> before, Map<String, Object> after, String... changed) {
        var original = new HashMap<>(before);
        var saved = new HashMap<>(after);
        for (String column : changed) {
            original.remove(column);
            saved.remove(column);
        }
        assertThat(saved).isEqualTo(original);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean SmsService smsService() { return sender; }
        @Bean CommitFaultDataSource dataSource(DataSourceProperties properties) {
            return new CommitFaultDataSource(properties.initializeDataSourceBuilder().build());
        }
    }

    /** Inject only after the real JDBC commit, leaving the application unable to confirm its outcome. */
    static class CommitFaultDataSource extends DelegatingDataSource implements AutoCloseable {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean tracking = new AtomicBoolean();
        private final AtomicInteger commitCalls = new AtomicInteger();
        private final AtomicInteger injectedFailures = new AtomicInteger();

        CommitFaultDataSource(DataSource target) { super(target); }
        void arm() {
            commitCalls.set(0);
            injectedFailures.set(0);
            tracking.set(true);
            armed.set(true);
        }
        void disarm() {
            armed.set(false);
            tracking.set(false);
        }
        @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return wrap(super.getConnection(username, password));
        }
        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        Object result;
                        try {
                            result = method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                        if (method.getName().equals("commit")) {
                            if (tracking.get()) commitCalls.incrementAndGet();
                            if (armed.compareAndSet(true, false)) {
                                injectedFailures.incrementAndGet();
                                throw new SQLException(DATABASE_DETAIL + " " + MESSAGE + " " + PHONE, "08006",
                                        new IllegalStateException(CAUSE_DETAIL));
                            }
                        }
                        return result;
                    });
        }
        @Override public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable target) target.close();
        }
    }
}
