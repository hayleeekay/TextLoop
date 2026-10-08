package com.haylee.textloop.reminder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.sms.SmsSubmissionResult;

/** Short committed units of work. The sender is deliberately absent from these transactions. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class DeliveryTransactions {
    private final ReminderRepository reminders;
    private final DeliveryRepository deliveries;
    private final Clock clock;

    public DeliveryTransactions(ReminderRepository reminders, DeliveryRepository deliveries, Clock clock) {
        this.reminders = reminders;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    public Reminder createReminder(CreateReminderRequest request) {
        Reminder reminder = reminders.save(new Reminder(request.message(), request.phoneNumber(), request.scheduledAt()));
        attachDelivery(reminder, false);
        return reminder;
    }

    public void initializeLegacy() {
        LocalDateTime cutover = now();
        for (Long id : reminders.findUntrackedPendingIds()) {
            Reminder reminder = reminders.findLockedById(id).orElseThrow();
            if (reminder.getStatus() == ReminderStatus.PENDING && reminder.getCurrentDeliveryId() == null) {
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
                    delivery.getId().equals(reminder.getCurrentDeliveryId()), reminder.getStatus(),
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

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record Submission(UUID deliveryId, int attemptCount, String phoneNumber, String message) {}

    /** Payload-free recovery output; version is the required token for every write. */
    public record DeliveryInspection(UUID deliveryId, Long reminderId, long version, DeliveryState state,
            DeliveryReason reason, int attemptCount, LocalDateTime nextAttemptAt, boolean extraAttemptPermitted,
            boolean retryExhausted, boolean current, ReminderStatus reminderStatus, RecoveryAction recoveryAction,
            LocalDateTime recoveredAt, UUID replacesDeliveryId) {}
}
