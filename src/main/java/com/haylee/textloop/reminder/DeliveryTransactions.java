package com.haylee.textloop.reminder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.EditReminderRequest;
import com.haylee.textloop.reminder.dto.RemindAgainRequest;
import com.haylee.textloop.sms.SmsSubmissionResult;

/** Short committed units of work. The sender is deliberately absent from these transactions. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class DeliveryTransactions {
    private final ReminderRepository reminders;
    private final DeliveryRepository deliveries;
    private final ReminderReuseRepository reuses;
    private final Clock clock;

    public DeliveryTransactions(ReminderRepository reminders, DeliveryRepository deliveries,
            ReminderReuseRepository reuses, Clock clock) {
        this.reminders = reminders;
        this.deliveries = deliveries;
        this.reuses = reuses;
        this.clock = clock;
    }

    public Reminder createReminder(CreateReminderRequest request) {
        LocalDateTime scheduledAt = canonicalSchedule(request.scheduledAt());
        requireFutureSchedule(scheduledAt);
        return persistReminder(request.message(), request.phoneNumber(), scheduledAt);
    }

    private Reminder persistReminder(String message, String phoneNumber, LocalDateTime scheduledAt) {
        Reminder reminder = reminders.save(new Reminder(message, phoneNumber, scheduledAt));
        attachDelivery(reminder, false);
        return reminder;
    }

    public Reminder remindAgain(Long id, RemindAgainRequest request) {
        Reminder source = reminders.findLockedById(id)
                .orElseThrow(() -> new ReminderMutationException(ReminderMutationException.Reason.NOT_FOUND));
        LocalDateTime scheduledAt = canonicalSchedule(request.scheduledAt());
        var previous = reuses.findBySourceReminderIdAndOperationId(id, request.operationId());
        if (previous.isPresent()) {
            // Replay resolves an already committed save, even after its time or source version changes.
            if (!previous.get().matches(request.expectedVersion(), scheduledAt)) {
                throw new ReminderMutationException(ReminderMutationException.Reason.OPERATION_CONFLICT);
            }
            return previous.get().getResultReminder();
        }
        if (source.getVersion() != request.expectedVersion()) {
            throw new ReminderMutationException(ReminderMutationException.Reason.STALE_VERSION);
        }
        if (source.getCurrentDeliveryId() == null) {
            if (source.getStatus() != ReminderStatus.SENT || source.getWithdrawnAt() != null) {
                throw new ReminderMutationException(ReminderMutationException.Reason.NOT_REUSABLE);
            }
        } else {
            Delivery delivery = deliveries.findLockedById(source.getCurrentDeliveryId())
                    .orElseThrow(() -> new ReminderMutationException(ReminderMutationException.Reason.NOT_REUSABLE));
            if (!delivery.getReminder().getId().equals(source.getId())) {
                throw new ReminderMutationException(ReminderMutationException.Reason.NOT_REUSABLE);
            }
            if (delivery.getState() == DeliveryState.UNKNOWN) {
                throw new ReminderMutationException(ReminderMutationException.Reason.DELIVERY_REVIEW_REQUIRED);
            }
            if (!DeliveryPolicy.reusable(delivery)) {
                throw new ReminderMutationException(ReminderMutationException.Reason.NOT_REUSABLE);
            }
        }
        requireFutureSchedule(scheduledAt);
        Reminder result = persistReminder(source.getMessage(), source.getPhoneNumber(), scheduledAt);
        reuses.save(new ReminderReuse(source, request.operationId(), request.expectedVersion(), scheduledAt, result));
        return result;
    }

    public Reminder editReminder(Long id, EditReminderRequest request) {
        Delivery previous = lockEditable(id, request.expectedVersion());
        LocalDateTime scheduledAt = canonicalSchedule(request.scheduledAt());
        requireFutureSchedule(scheduledAt);
        Reminder reminder = previous.getReminder();
        reminder.setMessage(request.message());
        reminder.setScheduledAt(scheduledAt);
        Delivery replacement = new Delivery(reminder, now(), false);
        replacement.linkPrevious(previous);
        deliveries.save(replacement);
        reminder.setCurrentDeliveryId(replacement.getId());
        return reminder;
    }

    public Reminder withdrawReminder(Long id, long expectedVersion) {
        Reminder reminder = lockEditable(id, expectedVersion).getReminder();
        // Keep the pointer and snapshot: a withdrawn alert is not an untracked legacy reminder.
        reminder.withdraw(now());
        return reminder;
    }

    private Delivery lockEditable(Long id, long expectedVersion) {
        Reminder reminder = reminders.findLockedById(id)
                .orElseThrow(() -> new ReminderMutationException(ReminderMutationException.Reason.NOT_FOUND));
        if (reminder.getVersion() != expectedVersion) {
            throw new ReminderMutationException(ReminderMutationException.Reason.STALE_VERSION);
        }
        if (reminder.getCurrentDeliveryId() == null) {
            throw new ReminderMutationException(ReminderMutationException.Reason.NOT_EDITABLE);
        }
        Delivery delivery = deliveries.findLockedById(reminder.getCurrentDeliveryId())
                .orElseThrow(() -> new ReminderMutationException(ReminderMutationException.Reason.NOT_EDITABLE));
        if (!delivery.getReminder().getId().equals(reminder.getId())
                || !currentPending(delivery) || !DeliveryPolicy.editable(delivery)) {
            throw new ReminderMutationException(ReminderMutationException.Reason.NOT_EDITABLE);
        }
        return delivery;
    }

    public void initializeLegacy() {
        LocalDateTime cutover = now();
        for (Long id : reminders.findUntrackedPendingIds()) {
            Reminder reminder = reminders.findLockedById(id).orElseThrow();
            if (reminder.getStatus() == ReminderStatus.PENDING && reminder.getWithdrawnAt() == null
                    && reminder.getCurrentDeliveryId() == null) {
                // The previous implementation cannot establish whether an overdue row already sent.
                attachDelivery(reminder, !reminder.getScheduledAt().isAfter(cutover));
            }
        }
    }

    public Optional<Submission> claim(UUID id, LocalDateTime cutoff) {
        Delivery delivery = lock(id);
        Reminder reminder = delivery.getReminder();
        if (!currentPending(delivery) || reminder.getScheduledAt().isAfter(cutoff)
                || !DeliveryPolicy.eligible(delivery, cutoff)) {
            return Optional.empty();
        }
        delivery.beginAttempt(now());
        return Optional.of(new Submission(id, delivery.getAttemptCount(), delivery.getPhoneNumber(), delivery.getMessage()));
    }

    public void recordResult(UUID id, int attemptCount, SmsSubmissionResult result) {
        Delivery delivery = lock(id);
        if (!currentPending(delivery) || delivery.getState() != DeliveryState.UNKNOWN
                || delivery.getAttemptCount() != attemptCount) {
            throw new RecoveryConflictException();
        }
        applyResult(delivery, result);
    }

    public boolean finalizeAccepted(UUID id) {
        Delivery delivery = lock(id);
        if (!currentPending(delivery) || delivery.getState() != DeliveryState.ACCEPTED) {
            return false;
        }
        delivery.getReminder().setStatus(ReminderStatus.SENT);
        return true;
    }

    public UUID recover(UUID id, long expectedVersion, RecoveryAction action, boolean acknowledgeDuplicateRisk) {
        Delivery delivery = lock(id);
        if (delivery.getVersion() != expectedVersion || !currentPending(delivery)) {
            throw new RecoveryConflictException();
        }
        DeliveryPolicy.requireRecoveryAction(delivery, action, acknowledgeDuplicateRisk);
        LocalDateTime now = now();
        UUID resultingId = id;
        switch (action) {
            case FINALIZE -> {
                delivery.getReminder().setStatus(ReminderStatus.SENT);
            }
            case CONFIRM_ACCEPTED -> {
                applyResult(delivery, SmsSubmissionResult.accepted(null));
            }
            case CONFIRM_RETRYABLE_REJECTION -> {
                applyResult(delivery, SmsSubmissionResult.retryableRejection());
            }
            case CONFIRM_PERMANENT_REJECTION -> {
                applyResult(delivery, SmsSubmissionResult.permanentRejection());
            }
            case RETRY_ONCE -> {
                delivery.permitExtraAttempt(now);
            }
            case REPLACE_UNKNOWN -> {
                Delivery replacement = new Delivery(delivery.getReminder(), now, false);
                replacement.replace(delivery);
                deliveries.save(replacement);
                delivery.getReminder().setCurrentDeliveryId(replacement.getId());
                resultingId = replacement.getId();
            }
        }
        // Version advances even for a permission grant, preventing replay after that permission is consumed.
        delivery.recover(action, now);
        return resultingId;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<DeliveryInspection> inspect() {
        return deliveries.findAllByOrderByCreatedAtAscIdAsc().stream().map(delivery -> {
            Reminder reminder = delivery.getReminder();
            return new DeliveryInspection(delivery.getId(), reminder.getId(), delivery.getVersion(),
                    delivery.getState(), delivery.getReason(), delivery.getAttemptCount(), delivery.getNextAttemptAt(),
                    delivery.isExtraAttemptPermitted(), DeliveryPolicy.exhausted(delivery),
                    delivery.getId().equals(reminder.getCurrentDeliveryId()), reminder.getStatus(), reminder.getWithdrawnAt(),
                    delivery.getRecoveryAction(), delivery.getRecoveredAt(), delivery.getReplacesDeliveryId());
        }).toList();
    }

    private Delivery lock(UUID id) {
        Long reminderId = deliveries.findReminderId(id).orElseThrow(RecoveryConflictException::new);
        reminders.findLockedById(reminderId).orElseThrow(RecoveryConflictException::new);
        return deliveries.findLockedById(id).orElseThrow(RecoveryConflictException::new);
    }

    private boolean currentPending(Delivery delivery) {
        Reminder reminder = delivery.getReminder();
        return reminder.getStatus() == ReminderStatus.PENDING
                && reminder.getWithdrawnAt() == null
                && delivery.getId().equals(reminder.getCurrentDeliveryId());
    }

    private void attachDelivery(Reminder reminder, boolean unknown) {
        Delivery delivery = deliveries.save(new Delivery(reminder, now(), unknown));
        reminder.setCurrentDeliveryId(delivery.getId());
    }

    private void applyResult(Delivery delivery, SmsSubmissionResult result) {
        LocalDateTime now = now();
        switch (result.outcome()) {
            case ACCEPTED -> delivery.recordOutcome(DeliveryState.ACCEPTED, DeliveryReason.ACCEPTANCE_CONFIRMED,
                    result.acceptanceReference(), null, now);
            case RETRYABLE_REJECTION -> delivery.recordOutcome(DeliveryState.RETRYABLE_REJECTION,
                    DeliveryReason.RETRYABLE_NON_ACCEPTANCE, null,
                    delivery.getAttemptCount() == 0 ? now : DeliveryPolicy.nextRetry(delivery.getAttemptCount(), now), now);
            case PERMANENT_REJECTION -> delivery.recordOutcome(DeliveryState.PERMANENT_REJECTION,
                    DeliveryReason.PERMANENT_NON_ACCEPTANCE, null, null, now);
            case UNKNOWN -> delivery.recordOutcome(DeliveryState.UNKNOWN, DeliveryReason.SUBMISSION_UNCONFIRMED,
                    null, null, now);
        }
    }

    private LocalDateTime canonicalSchedule(LocalDateTime requested) {
        return requested.truncatedTo(ChronoUnit.MICROS);
    }

    private void requireFutureSchedule(LocalDateTime scheduledAt) {
        // Compare the value that will be stored against the full validation clock.
        if (!scheduledAt.isAfter(now())) {
            throw new ReminderMutationException(ReminderMutationException.Reason.INVALID_SCHEDULE);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record Submission(UUID deliveryId, int attemptCount, String phoneNumber, String message) {}

    /** Payload-free recovery output; version is the required token for every write. */
    public record DeliveryInspection(UUID deliveryId, Long reminderId, long version, DeliveryState state,
            DeliveryReason reason, int attemptCount, LocalDateTime nextAttemptAt, boolean extraAttemptPermitted,
            boolean retryExhausted, boolean current, ReminderStatus reminderStatus, LocalDateTime withdrawnAt,
            RecoveryAction recoveryAction,
            LocalDateTime recoveredAt, UUID replacesDeliveryId) {}
}
