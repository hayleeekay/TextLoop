package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ExitCodeEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;

import com.haylee.textloop.LoggingStateExtension;
import com.haylee.textloop.TextloopApplication;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.scheduler.ReminderScheduler;
import com.haylee.textloop.sms.SmsService;
import com.haylee.textloop.sms.SmsSubmissionResult;

@ExtendWith({LoggingStateExtension.class, OutputCaptureExtension.class})
class DeliveryRecoveryIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @BeforeAll
    static void startPostgres() { postgres.start(); }

    @AfterAll
    static void stopPostgres() { postgres.stop(); }

    private static SmsService sender;
    private static MutableClock clock;
    private ConfigurableApplicationContext context;
    private static DeliveryTransactions failedTransactions;
    private boolean failContextStartup;
    private final List<Integer> startupExitCodes = new ArrayList<>();

    @BeforeEach
    void startFreshDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("drop schema public cascade");
            statement.execute("create schema public");
        }
        failedTransactions = null;
        failContextStartup = false;
        sender = mock(SmsService.class);
        clock = new MutableClock();
        when(sender.sendSms(any(), anyString(), anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return SmsSubmissionResult.accepted(null);
        });
        boot();
    }

    @AfterEach
    void closeApplication() {
        if (context != null) {
            context.close();
        }
    }

    private void boot(String... overrides) {
        if (context != null) {
            context.close();
            context = null;
        }
        var properties = new LinkedHashMap<String, String>();
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("spring.jpa.hibernate.ddl-auto", "update");
        properties.put("spring.jpa.show-sql", "false");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.web-application-type", "none");
        properties.put("textloop.scheduling.enabled", "false");
        properties.put("logging.level.root", "WARN");
        properties.put("logging.level.com.haylee.textloop.reminder.DeliveryRecoveryCommand", "INFO");
        for (String override : overrides) {
            var parts = override.split("=", 2);
            properties.put(parts[0], parts[1]);
        }
        var application = TextloopApplication.application();
        application.setLogStartupInfo(false);
        application.addPrimarySources(List.of(Fixtures.class));
        if (failedTransactions != null) {
            application.addPrimarySources(List.of(FailedTransactionsConfiguration.class));
        }
        if (failContextStartup) {
            application.addPrimarySources(List.of(FailedContextConfiguration.class));
        }
        application.addListeners((ApplicationListener<ExitCodeEvent>) event -> startupExitCodes.add(event.getExitCode()));
        context = application.run(properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new));
    }

    private ReminderService reminders() { return context.getBean(ReminderService.class); }
    private ReminderRepository reminderRows() { return context.getBean(ReminderRepository.class); }
    private DeliveryRepository deliveryRows() { return context.getBean(DeliveryRepository.class); }
    private DeliveryTransactions transactions() { return context.getBean(DeliveryTransactions.class); }
    private JdbcTemplate jdbc() { return context.getBean(JdbcTemplate.class); }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private void pass() { reminders().processDueReminders(now()); }

    private Delivery create(String message) {
        var response = reminders().createReminder(new CreateReminderRequest(message, now().minusMinutes(1), "+12035550100"));
        return deliveryRows().findById(reminderRows().findById(response.id()).orElseThrow().getCurrentDeliveryId()).orElseThrow();
    }

    private Delivery reload(UUID id) { return deliveryRows().findById(id).orElseThrow(); }
    private ReminderStatus reminderStatus(Delivery delivery) {
        return reminderRows().findById(delivery.getReminder().getId()).orElseThrow().getStatus();
    }

    @Test
    void retryableRejectionsHavePersistedBudgetAndDoNotBlockHealthyReminders() {
        Delivery failing = create("Transient failure");
        Delivery healthy = create("Healthy reminder");
        when(sender.sendSms(eq(failing.getId()), anyString(), anyString())).thenReturn(SmsSubmissionResult.retryableRejection());

        pass();
        assertThat(reload(failing.getId()).getNextAttemptAt()).isEqualTo(now().plusMinutes(1));
        assertThat(reminderStatus(healthy)).isEqualTo(ReminderStatus.SENT);
        clock.advance(Duration.ofSeconds(59));
        pass();
        verify(sender, times(1)).sendSms(eq(failing.getId()), anyString(), anyString());
        boot();
        clock.advance(Duration.ofSeconds(1));
        pass();
        assertThat(reload(failing.getId()).getAttemptCount()).isEqualTo(2);
        assertThat(reload(failing.getId()).getNextAttemptAt()).isEqualTo(now().plusMinutes(5));
        clock.advance(Duration.ofMinutes(4));
        Delivery laterHealthy = create("Another healthy reminder");
        pass();
        assertThat(reminderStatus(laterHealthy)).isEqualTo(ReminderStatus.SENT);
        clock.advance(Duration.ofMinutes(1));
        pass();
        assertThat(reload(failing.getId()).getAttemptCount()).isEqualTo(3);
        assertThat(reload(failing.getId()).getNextAttemptAt()).isNull();
        boot();
        clock.advance(Duration.ofDays(1));
        pass();
        verify(sender, times(3)).sendSms(eq(failing.getId()), anyString(), anyString());
        verify(sender, times(1)).sendSms(eq(healthy.getId()), anyString(), anyString());
        verify(sender, times(1)).sendSms(eq(laterHealthy.getId()), anyString(), anyString());
        assertThat(transactions().inspect().stream().filter(row -> row.deliveryId().equals(failing.getId())).findFirst().orElseThrow().retryExhausted()).isTrue();
    }

    @Test
    void transientFailureCanRecoverOnSecondAttempt() {
        Delivery delivery = create("Retry me");
        when(sender.sendSms(eq(delivery.getId()), anyString(), anyString()))
                .thenReturn(SmsSubmissionResult.retryableRejection(), SmsSubmissionResult.accepted("opaque-reference"));
        pass();
        clock.advance(Duration.ofMinutes(1));
        pass();
        pass();
        assertThat(reload(delivery.getId()).getAcceptanceReference()).isEqualTo("opaque-reference");
        assertThat(reminderStatus(delivery)).isEqualTo(ReminderStatus.SENT);
        verify(sender, times(2)).sendSms(eq(delivery.getId()), anyString(), anyString());
    }

    @Test
    void retryDelayStartsAtFailureCompletionRatherThanBatchCutoff() {
        Delivery delivery = create("Slow rejection");
        when(sender.sendSms(eq(delivery.getId()), anyString(), anyString())).thenAnswer(invocation -> {
            clock.advance(Duration.ofMinutes(2));
            return SmsSubmissionResult.retryableRejection();
        });
        pass();
        assertThat(reload(delivery.getId()).getNextAttemptAt()).isEqualTo(now().plusMinutes(1));
        pass();
        verify(sender, times(1)).sendSms(eq(delivery.getId()), anyString(), anyString());
    }

    @Test
    void permanentRejectionAndAmbiguousAcceptanceRemainHeldAcrossRestart() {
        Delivery permanent = create("Permanent rejection");
        Delivery uncertain = create("May already have been accepted");
        Delivery healthy = create("Healthy");
        when(sender.sendSms(eq(permanent.getId()), anyString(), anyString())).thenReturn(SmsSubmissionResult.permanentRejection());
        when(sender.sendSms(eq(uncertain.getId()), anyString(), anyString())).thenReturn(SmsSubmissionResult.unknown());
        pass();
        boot();
        clock.advance(Duration.ofDays(1));
        pass();
        assertThat(reload(permanent.getId()).getState()).isEqualTo(DeliveryState.PERMANENT_REJECTION);
        assertThat(reload(uncertain.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        assertThat(reminderStatus(healthy)).isEqualTo(ReminderStatus.SENT);
        verify(sender, times(1)).sendSms(eq(permanent.getId()), anyString(), anyString());
        verify(sender, times(1)).sendSms(eq(uncertain.getId()), anyString(), anyString());
        assertThatThrownBy(() -> transactions().recover(permanent.getId(), reload(permanent.getId()).getVersion(), RecoveryAction.RETRY_ONCE, false))
                .isInstanceOf(RecoveryConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void maximumRenderedSnapshotSurvivesPersistenceAndRetry(boolean unicode, CapturedOutput output) {
        String raw = unicode ? "\uD83D\uDE00".repeat(500) : "m".repeat(1000);
        Delivery delivery = create(raw);
        String snapshot = Delivery.renderMessage(raw);
        assertThat(snapshot.length()).isGreaterThan(1000);
        assertThat(reload(delivery.getId()).getMessage()).isEqualTo(snapshot);
        when(sender.sendSms(eq(delivery.getId()), anyString(), anyString()))
                .thenReturn(SmsSubmissionResult.retryableRejection(), SmsSubmissionResult.accepted(null));
        pass();
        Reminder reminder = reminderRows().findById(delivery.getReminder().getId()).orElseThrow();
        reminder.setMessage("Changed after intent creation");
        reminderRows().save(reminder);
        boot();
        clock.advance(Duration.ofMinutes(1));
        pass();
        verify(sender, times(2)).sendSms(delivery.getId(), "+12035550100", snapshot);
        assertThat(output).doesNotContain(raw, "+12035550100");
        assertThat(reminderStatus(delivery)).isEqualTo(ReminderStatus.SENT);
    }

    @Test
    void intentAndReminderCreationAreAtomicAtCommit() {
        failCommit("deliveries", "NEW.state = 'READY'");
        assertThatThrownBy(() -> create("Atomic intent")).isInstanceOf(RuntimeException.class);
        assertThat(reminderRows().count()).isZero();
        assertThat(deliveryRows().count()).isZero();
        verifyNoInteractions(sender);
    }

    @Test
    void preSendCommitFailurePreventsSubmissionAndLeavesReady() {
        Delivery delivery = create("Not yet submitted");
        failCommit("deliveries", "NEW.state = 'UNKNOWN'");
        assertThatThrownBy(this::pass).isInstanceOf(RuntimeException.class);
        assertThat(reload(delivery.getId()).getState()).isEqualTo(DeliveryState.READY);
        assertThat(reload(delivery.getId()).getAttemptCount()).isZero();
        verifyNoInteractions(sender);
        removeCommitFailure("deliveries");
        boot();
        pass();
        assertThat(reminderStatus(delivery)).isEqualTo(ReminderStatus.SENT);
    }

    @Test
    void acceptanceCommitFailureLeavesUnknownAndNeverBlindlyResends() {
        Delivery delivery = create("Accepted before recording failed");
        failCommit("deliveries", "NEW.state = 'ACCEPTED'");
        assertThatThrownBy(this::pass).isInstanceOf(RuntimeException.class);
        assertThat(reload(delivery.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        removeCommitFailure("deliveries");
        boot();
        pass();
        verify(sender, times(1)).sendSms(eq(delivery.getId()), anyString(), anyString());
        transactions().recover(delivery.getId(), reload(delivery.getId()).getVersion(), RecoveryAction.CONFIRM_ACCEPTED, false);
        pass();
        assertThat(reminderStatus(delivery)).isEqualTo(ReminderStatus.SENT);
        verify(sender, times(1)).sendSms(eq(delivery.getId()), anyString(), anyString());
    }

    @Test
    void acceptanceSurvivesFailedReminderCommitAndRestartWithoutAnotherSubmission() {
        Delivery delivery = create("Accepted but not finalized");
        failCommit("reminders", "NEW.status = 'SENT'");
        assertThatThrownBy(this::pass).isInstanceOf(RuntimeException.class);
        assertThat(reload(delivery.getId()).getState()).isEqualTo(DeliveryState.ACCEPTED);
        assertThat(reminderStatus(delivery)).isEqualTo(ReminderStatus.PENDING);
        removeCommitFailure("reminders");
        boot();
        pass();
        assertThat(reminderStatus(delivery)).isEqualTo(ReminderStatus.SENT);
        verify(sender, times(1)).sendSms(eq(delivery.getId()), anyString(), anyString());
    }

    @Test
    void rejectionPersistenceFailureIsNotAnOrdinaryRetry() {
        Delivery delivery = create("Rejection before failed commit");
        when(sender.sendSms(eq(delivery.getId()), anyString(), anyString())).thenReturn(SmsSubmissionResult.retryableRejection());
        failCommit("deliveries", "NEW.state = 'RETRYABLE_REJECTION'");
        assertThatThrownBy(this::pass).isInstanceOf(RuntimeException.class);
        removeCommitFailure("deliveries");
        boot();
        clock.advance(Duration.ofHours(1));
        pass();
        assertThat(reload(delivery.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        verify(sender, times(1)).sendSms(eq(delivery.getId()), anyString(), anyString());
    }

    @Test
    void interruptedDeliveryAfterClaimStaysUnknownEvenWithoutSenderCall() {
        Delivery delivery = create("Interrupted before submitting");
        assertThat(transactions().claim(delivery.getId(), now())).isPresent();
        boot();
        pass();
        assertThat(reload(delivery.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        verifyNoInteractions(sender);
        transactions().recover(delivery.getId(), reload(delivery.getId()).getVersion(), RecoveryAction.CONFIRM_RETRYABLE_REJECTION, false);
        clock.advance(Duration.ofMinutes(1));
        pass();
        assertThat(reminderStatus(delivery)).isEqualTo(ReminderStatus.SENT);
    }

    @Test
    void unexpectedFailurePropagatesAndNextPassSkipsItsUnknownIntent() {
        Delivery broken = create("Unclassified failure");
        Delivery healthy = create("Later reminder");
        var failure = new IllegalStateException("private-sender-marker +12035550100");
        when(sender.sendSms(eq(broken.getId()), anyString(), anyString())).thenThrow(failure);
        assertThatThrownBy(this::pass).isSameAs(failure);
        pass();
        assertThat(reload(broken.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        assertThat(reminderStatus(healthy)).isEqualTo(ReminderStatus.SENT);
        verify(sender, times(1)).sendSms(eq(broken.getId()), anyString(), anyString());
    }

    @Test
    void consumedExtraPermissionCannotBeGrantedAgainWithTheOldCommandToken() {
        Delivery delivery = create("Repeated rejection");
        when(sender.sendSms(eq(delivery.getId()), anyString(), anyString())).thenReturn(SmsSubmissionResult.retryableRejection());
        pass();
        clock.advance(Duration.ofMinutes(1));
        pass();
        clock.advance(Duration.ofMinutes(5));
        pass();
        long exhaustedVersion = reload(delivery.getId()).getVersion();
        boot("textloop.recovery=true", "recovery.action=retry-once", "recovery.delivery=" + delivery.getId(),
                "recovery.version=" + exhaustedVersion, "recovery.app-stopped=true");
        assertThat(context.getBean(DeliveryRecoveryCommand.class).getExitCode()).isZero();
        assertThatThrownBy(() -> transactions().recover(delivery.getId(), exhaustedVersion, RecoveryAction.RETRY_ONCE, false))
                .isInstanceOf(RecoveryConflictException.class);
        boot();
        pass();
        assertThat(reload(delivery.getId()).getAttemptCount()).isEqualTo(4);
        assertThat(reload(delivery.getId()).isExtraAttemptPermitted()).isFalse();
        assertThatThrownBy(() -> transactions().recover(delivery.getId(), exhaustedVersion, RecoveryAction.RETRY_ONCE, false))
                .isInstanceOf(RecoveryConflictException.class);
        boot("textloop.recovery=true", "recovery.action=retry-once", "recovery.delivery=" + delivery.getId(),
                "recovery.version=" + exhaustedVersion, "recovery.app-stopped=true");
        assertThat(context.getBean(DeliveryRecoveryCommand.class).getExitCode()).isEqualTo(2);
        boot();
        clock.advance(Duration.ofHours(1));
        pass();
        verify(sender, times(4)).sendSms(eq(delivery.getId()), anyString(), anyString());
    }

    @Test
    void unknownReplacementRequiresAcknowledgementAndPreservesOriginalUncertainty() {
        Delivery original = create("Preserve my original snapshot");
        transactions().claim(original.getId(), now());
        long version = reload(original.getId()).getVersion();
        assertThatThrownBy(() -> transactions().recover(original.getId(), version, RecoveryAction.REPLACE_UNKNOWN, false))
                .isInstanceOf(RecoveryConflictException.class);
        assertThat(reload(original.getId()).getVersion()).isEqualTo(version);
        UUID replacement = transactions().recover(original.getId(), version, RecoveryAction.REPLACE_UNKNOWN, true);
        assertThat(reload(original.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        assertThat(reload(replacement).getReplacesDeliveryId()).isEqualTo(original.getId());
        assertThat(reload(replacement).getMessage()).isEqualTo(original.getMessage());
        assertThatThrownBy(() -> transactions().recover(original.getId(), reload(original.getId()).getVersion(), RecoveryAction.CONFIRM_ACCEPTED, false))
                .isInstanceOf(RecoveryConflictException.class);
        pass();
        verify(sender).sendSms(eq(replacement), anyString(), anyString());
        verify(sender, never()).sendSms(eq(original.getId()), anyString(), anyString());
    }

    @Test
    void replacementCommitFailureRollsBackPointerRecordAndRecoveryMetadata() {
        Delivery original = create("Atomic replacement");
        transactions().claim(original.getId(), now());
        long version = reload(original.getId()).getVersion();
        failCommit("deliveries", "NEW.replaces_delivery_id is not null");
        assertThatThrownBy(() -> transactions().recover(original.getId(), version, RecoveryAction.REPLACE_UNKNOWN, true))
                .isInstanceOf(RuntimeException.class);
        assertThat(deliveryRows().count()).isEqualTo(1);
        assertThat(reminderRows().findById(original.getReminder().getId()).orElseThrow().getCurrentDeliveryId())
                .isEqualTo(original.getId());
        assertThat(reload(original.getId()).getVersion()).isEqualTo(version);
        assertThat(reload(original.getId()).getRecoveryAction()).isNull();
        assertThat(reload(original.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        verifyNoInteractions(sender);
    }

    @Test
    void olderAcceptanceCannotFinalizeANewerIntent() {
        Delivery old = create("Original occurrence");
        var submission = transactions().claim(old.getId(), now()).orElseThrow();
        transactions().recordResult(old.getId(), submission.attemptCount(), SmsSubmissionResult.accepted(null));
        Reminder reminder = reminderRows().findById(old.getReminder().getId()).orElseThrow();
        Delivery next = deliveryRows().save(new Delivery(reminder, now(), false));
        reminder.setCurrentDeliveryId(next.getId());
        reminderRows().save(reminder);
        assertThat(transactions().finalizeAccepted(old.getId())).isFalse();
        assertThat(reminderStatus(old)).isEqualTo(ReminderStatus.PENDING);
        assertThatThrownBy(() -> transactions().recover(old.getId(), reload(old.getId()).getVersion(), RecoveryAction.FINALIZE, false))
                .isInstanceOf(RecoveryConflictException.class);
        pass();
        verify(sender).sendSms(eq(next.getId()), anyString(), anyString());
        verify(sender, never()).sendSms(eq(old.getId()), anyString(), anyString());
    }

    @Test
    void concurrentRecoveryWritesWithOneTokenHaveOnlyOneWinner() throws Exception {
        Delivery delivery = create("Concurrent recovery");
        transactions().claim(delivery.getId(), now());
        long version = reload(delivery.getId()).getVersion();
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<CompletableFuture<Boolean>>();
        for (int i = 0; i < 2; i++) {
            outcomes.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    transactions().recover(delivery.getId(), version, RecoveryAction.CONFIRM_PERMANENT_REJECTION, false);
                    return true;
                } catch (RecoveryConflictException expected) {
                    return false;
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            }));
        }
        start.countDown();
        assertThat(List.of(outcomes.get(0).get(), outcomes.get(1).get())).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void legacyCutoverHoldsOverdueRowsPreservesSentAndIsIdempotent() {
        Reminder overdue = reminderRows().save(new Reminder("Legacy unknown", "+12035550100", now().minusMinutes(1)));
        Reminder future = reminderRows().save(new Reminder("Never due", "+12035550101", now().plusHours(1)));
        Reminder sent = new Reminder("Legacy sent", "+12035550102", now().minusMinutes(1));
        sent.setStatus(ReminderStatus.SENT);
        reminderRows().save(sent);
        boot();
        transactions().initializeLegacy();
        assertThat(deliveryRows().count()).isEqualTo(2);
        UUID overdueId = reminderRows().findById(overdue.getId()).orElseThrow().getCurrentDeliveryId();
        UUID futureId = reminderRows().findById(future.getId()).orElseThrow().getCurrentDeliveryId();
        assertThat(reload(overdueId).getReason()).isEqualTo(DeliveryReason.LEGACY_UNRECORDED);
        assertThat(reload(overdueId).getState()).isEqualTo(DeliveryState.UNKNOWN);
        assertThat(reload(futureId).getState()).isEqualTo(DeliveryState.READY);
        assertThat(reminderRows().findById(sent.getId()).orElseThrow().getCurrentDeliveryId()).isNull();
        pass();
        verifyNoInteractions(sender);
    }

    @Test
    void actualRecoveryStartupCannotServeHttpOrAutomaticallySend(CapturedOutput output) {
        Delivery ready = create("private-recovery-marker");
        boot("textloop.recovery=true", "textloop.scheduling.enabled=true",
                "spring.main.web-application-type=servlet", "textloop.reminders.check-interval-ms=10");
        assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBeansOfType(ReminderScheduler.class)).isEmpty();
        assertThat(context.getBean(DeliveryRecoveryCommand.class).getExitCode()).isZero();
        assertThat(reload(ready.getId()).getState()).isEqualTo(DeliveryState.READY);
        assertThat(reload(ready.getId()).getAttemptCount()).isZero();
        verifyNoInteractions(sender);
        assertThat(output).contains("Delivery inspection", ready.getId().toString(), "version=", "state=READY")
                .doesNotContain("private-recovery-marker", "+12035550100");
    }

    @Test
    void recoveryCommandRequiresStoppedAppAndFreshVersionAndCanFinalizeAcceptance() {
        Delivery delivery = create("Command recovery");
        transactions().claim(delivery.getId(), now());
        long version = reload(delivery.getId()).getVersion();
        boot("textloop.recovery=true", "recovery.action=confirm-accepted", "recovery.delivery=" + delivery.getId(), "recovery.version=" + version);
        assertThat(context.getBean(DeliveryRecoveryCommand.class).getExitCode()).isEqualTo(2);
        assertThat(reload(delivery.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        boot("textloop.recovery=true", "recovery.action=confirm-accepted", "recovery.delivery=" + delivery.getId(), "recovery.version=" + version, "recovery.app-stopped=true");
        assertThat(context.getBean(DeliveryRecoveryCommand.class).getExitCode()).isZero();
        boot("textloop.recovery=true", "recovery.action=confirm-accepted", "recovery.delivery=" + delivery.getId(), "recovery.version=" + version, "recovery.app-stopped=true");
        assertThat(context.getBean(DeliveryRecoveryCommand.class).getExitCode()).isEqualTo(2);
        boot("textloop.recovery=true", "recovery.action=finalize", "recovery.delivery=" + delivery.getId(),
                "recovery.version=" + reload(delivery.getId()).getVersion(), "recovery.app-stopped=true");
        assertThat(context.getBean(DeliveryRecoveryCommand.class).getExitCode()).isZero();
        assertThat(reminderStatus(delivery)).isEqualTo(ReminderStatus.SENT);
        verifyNoInteractions(sender);
    }

    @Test
    void actualScheduledErrorBoundaryReportsFailureWithoutThrowablePayloadAndKeepsRunning(CapturedOutput output) {
        Delivery broken = create("private-scheduler-marker");
        Delivery healthy = create("Healthy scheduled delivery");
        when(sender.sendSms(eq(broken.getId()), anyString(), anyString()))
                .thenThrow(new IllegalStateException("private-exception-marker +12035550100", new RuntimeException("private-cause-marker")));
        boot("textloop.scheduling.enabled=true", "textloop.reminders.check-interval-ms=50");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(output).contains("Reminder processing failed. Inspect delivery recovery state.", "category=UNEXPECTED");
            assertThat(reminderStatus(healthy)).isEqualTo(ReminderStatus.SENT);
        });
        assertThat(output).doesNotContain("private-scheduler-marker", "private-exception-marker", "private-cause-marker", "+12035550100");
        assertThat(reload(broken.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        verify(sender, times(1)).sendSms(eq(broken.getId()), anyString(), anyString());
    }

    @Test
    void upgradesActualLegacySchemaBeforeAdoptingPendingRows() throws Exception {
        context.close();
        context = null;
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement();
                var schema = getClass().getResourceAsStream("/reminder-legacy-schema.sql")) {
            statement.execute("drop schema public cascade");
            statement.execute("create schema public");
            statement.execute(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
            statement.execute("insert into reminders (message, phone_number, scheduled_at, status, created_at, updated_at) "
                    + "values ('legacy overdue', '+12035550100', '2000-01-01', 'PENDING', '2000-01-01', '2000-01-01')");
            statement.execute("insert into reminders (message, phone_number, scheduled_at, status, created_at, updated_at) "
                    + "values ('legacy future', '+12035550101', '2100-01-01', 'PENDING', '2000-01-01', '2000-01-01')");
        }
        boot();
        assertThat(reminderRows().count()).isEqualTo(3);
        assertThat(deliveryRows().count()).isEqualTo(2);
        var states = transactions().inspect();
        assertThat(states).extracting(DeliveryTransactions.DeliveryInspection::state)
                .containsExactlyInAnyOrder(DeliveryState.UNKNOWN, DeliveryState.READY);
        var oldSent = reminderRows().findById(1L).orElseThrow();
        assertThat(oldSent.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(oldSent.getMessage()).isEqualTo("m".repeat(255));
        assertThat(oldSent.getCreatedAt()).isEqualTo(LocalDateTime.of(2000, 1, 1, 10, 0));
        assertThat(oldSent.getCurrentDeliveryId()).isNull();
        pass();
        verifyNoInteractions(sender);
    }

    @Test
    void scheduledDatabaseCommitFailureDoesNotExposeDatabaseExceptionText(CapturedOutput output) {
        Delivery delivery = create("private-database-payload");
        failCommit("deliveries", "NEW.state = 'ACCEPTED'");
        boot("textloop.scheduling.enabled=true", "textloop.reminders.check-interval-ms=50");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(output).contains("Reminder processing failed. Inspect delivery recovery state.", "category=DATABASE"));
        assertThat(output).doesNotContain("private-database-marker", "private-database-payload", "+12035550100");
        assertThat(reload(delivery.getId()).getState()).isEqualTo(DeliveryState.UNKNOWN);
        verify(sender, times(1)).sendSms(eq(delivery.getId()), anyString(), anyString());
    }

    @Test
    void recoveryAcceptanceCommitFailureIsReportedSafelyAndPreservesUnknown(CapturedOutput output) {
        Delivery delivery = create("private-recovery-commit-payload");
        transactions().claim(delivery.getId(), now());
        long version = reload(delivery.getId()).getVersion();
        failCommitWithPayload("NEW.state = 'ACCEPTED'");

        assertThatThrownBy(() -> boot("textloop.recovery=true", "recovery.action=confirm-accepted",
                "recovery.delivery=" + delivery.getId(), "recovery.version=" + version, "recovery.app-stopped=true"))
                .isInstanceOf(RuntimeException.class);

        assertThat(startupExitCodes).containsExactly(1);
        assertUnknownUnchanged(delivery.getId(), version);
        assertThat(output).contains("Recovery startup or execution failed. Inspect delivery state before retrying.")
                .doesNotContain("private-recovery-commit-payload", "private-recovery-database-marker", "+12035550100",
                        "PSQLException", "Caused by:", "Recovery refused");
        verifyNoInteractions(sender);
    }

    @Test
    void recoveryAdoptionCommitFailureIsReportedSafelyBeforeTheCommandRuns(CapturedOutput output) {
        Delivery existing = create("Existing unknown");
        transactions().claim(existing.getId(), now());
        long version = reload(existing.getId()).getVersion();
        Reminder legacy = reminderRows().save(new Reminder("private-adoption-payload", "+12035550100", now().minusMinutes(1)));
        failCommitWithPayload("NEW.state = 'UNKNOWN'");

        assertThatThrownBy(() -> boot("textloop.recovery=true")).isInstanceOf(RuntimeException.class);

        assertThat(startupExitCodes).containsExactly(1);
        assertUnknownUnchanged(existing.getId(), version);
        assertThat(independentJdbc().queryForObject("select count(*) from deliveries", Long.class)).isEqualTo(1L);
        assertThat(independentJdbc().queryForObject("select current_delivery_id from reminders where id = ?", UUID.class, legacy.getId())).isNull();
        assertThat(output).contains("Recovery startup or execution failed. Inspect delivery state before retrying.")
                .doesNotContain("private-adoption-payload", "private-recovery-database-marker", "+12035550100", "Caused by:", "Delivery inspection");
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"adoption", "listing", "execution"})
    void recoveryProgrammingFailuresKeepNestedCausesOutOfStartupOutput(String phase, CapturedOutput output) {
        Delivery delivery = create("Unknown stays durable");
        transactions().claim(delivery.getId(), now());
        long version = reload(delivery.getId()).getVersion();
        failedTransactions = mock(DeliveryTransactions.class);
        RuntimeException failure = new IllegalStateException("private-programming-payload +12035550100",
                new IllegalArgumentException("private-nested-cause-marker"));
        switch (phase) {
            case "adoption" -> doThrow(failure).when(failedTransactions).initializeLegacy();
            case "listing" -> when(failedTransactions.inspect()).thenThrow(failure);
            case "execution" -> when(failedTransactions.recover(any(), anyLong(), any(), anyBoolean())).thenThrow(failure);
            default -> throw new IllegalArgumentException();
        }

        assertThatThrownBy(() -> boot("textloop.recovery=true", "recovery.action=" + (phase.equals("execution") ? "confirm-accepted" : "list"),
                "recovery.delivery=" + delivery.getId(), "recovery.version=" + version, "recovery.app-stopped=true"))
                .isInstanceOf(RuntimeException.class);

        assertThat(startupExitCodes).containsExactly(1);
        assertUnknownUnchanged(delivery.getId(), version);
        assertThat(output).contains("Recovery startup or execution failed. Inspect delivery state before retrying.")
                .doesNotContain("private-programming-payload", "private-nested-cause-marker", "+12035550100", "Caused by:", "Recovery refused");
        verifyNoInteractions(sender);
    }

    @Test
    void recoveryContextInitializationFailureIsReportedSafely(CapturedOutput output) {
        failContextStartup = true;
        assertThatThrownBy(() -> boot("textloop.recovery=true")).isInstanceOf(RuntimeException.class);
        assertThat(output).contains("Recovery startup or execution failed. Inspect delivery state before retrying.")
                .doesNotContain("private-startup-payload", "private-startup-nested-cause", "+12035550100", "Caused by:");
        verifyNoInteractions(sender);
    }

    @Test
    void realRecoveryEntryPointExitsNonzeroWithoutPrintingFailedCommitDetails() throws Exception {
        Delivery delivery = create("private-process-payload");
        transactions().claim(delivery.getId(), now());
        long version = reload(delivery.getId()).getVersion();
        failCommitWithPayload("NEW.state = 'ACCEPTED'");
        context.close();
        context = null;
        var result = runRecoveryProcess("--recovery.action=confirm-accepted", "--recovery.delivery=" + delivery.getId(),
                "--recovery.version=" + version, "--recovery.app-stopped=true");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("Recovery startup or execution failed. Inspect delivery state before retrying.")
                .doesNotContain("private-process-payload", "private-recovery-database-marker", "+12035550100", "Caused by:", "[FAKE SMS]");
        assertUnknownUnchanged(delivery.getId(), version);
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @ValueSource(strings = {"driver", "binding"})
    void realRecoveryStartupFailureBeforeRunnersExitsNonzeroWithoutPayload(String phase) throws Exception {
        Delivery delivery = create("Unknown before startup failure");
        transactions().claim(delivery.getId(), now());
        long version = reload(delivery.getId()).getVersion();
        context.close();
        context = null;
        String property = phase.equals("driver") ? "spring.datasource.driver-class-name" : "spring.main.banner-mode";
        var result = runRecoveryProcess("--" + property + "=private-startup-payload.+12035550100");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Recovery startup or execution failed. Inspect delivery state before retrying.")
                .doesNotContain("private-startup-payload", "+12035550100", "Caused by:", "[FAKE SMS]");
        assertUnknownUnchanged(delivery.getId(), version);
        verifyNoInteractions(sender);
    }

    private ProcessResult runRecoveryProcess(String... options) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), TextloopApplication.class.getName(),
                "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(), "--spring.jpa.hibernate.ddl-auto=update",
                "--spring.jpa.show-sql=false", "--spring.main.banner-mode=off", "--textloop.recovery=true"));
        command.addAll(List.of(options));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            return new ProcessResult(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    private record ProcessResult(int exitCode, String output) {}

    private JdbcTemplate independentJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private void assertUnknownUnchanged(UUID id, long version) {
        assertThat(independentJdbc().queryForObject("select state from deliveries where id = ?", String.class, id)).isEqualTo("UNKNOWN");
        assertThat(independentJdbc().queryForObject("select version from deliveries where id = ?", Long.class, id)).isEqualTo(version);
    }

    private void failCommitWithPayload(String condition) {
        jdbc().execute("create function fail_commit() returns trigger language plpgsql as $$ begin if " + condition
                + " then raise exception 'private-recovery-database-marker % %', NEW.message, NEW.phone_number; end if; return new; end $$");
        jdbc().execute("create constraint trigger failure_at_commit after insert or update on deliveries "
                + "deferrable initially deferred for each row execute function fail_commit()");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailedTransactionsConfiguration {
        @Bean @Primary DeliveryTransactions failedRecoveryTransactions() { return failedTransactions; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailedContextConfiguration {
        @Bean Object failedStartupBean() {
            throw new IllegalStateException("private-startup-payload +12035550100", new RuntimeException("private-startup-nested-cause"));
        }
    }

    private void failCommit(String table, String condition) {
        jdbc().execute("create function fail_commit() returns trigger language plpgsql as $$ begin if " + condition
                + " then raise exception 'private-database-marker'; end if; return new; end $$");
        jdbc().execute("create constraint trigger failure_at_commit after insert or update on " + table
                + " deferrable initially deferred for each row execute function fail_commit()");
    }

    private void removeCommitFailure(String table) {
        jdbc().execute("drop trigger failure_at_commit on " + table);
        jdbc().execute("drop function fail_commit()");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary SmsService testSender() { return sender; }
        @Bean @Primary Clock testClock() { return clock; }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-10-08T12:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
