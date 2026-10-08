package com.haylee.textloop.reminder;

import java.time.LocalDateTime;

/** Eligibility and retry budget are shared by scheduled delivery and local recovery. */
final class DeliveryPolicy {
    private static final int AUTOMATIC_ATTEMPT_LIMIT = 3;

    private DeliveryPolicy() {}

    static boolean eligible(Delivery delivery, LocalDateTime now) {
        if (delivery.getState() == DeliveryState.READY) {
            return true;
        }
        return delivery.getState() == DeliveryState.RETRYABLE_REJECTION
                && (delivery.getAttemptCount() < AUTOMATIC_ATTEMPT_LIMIT || delivery.isExtraAttemptPermitted())
                && delivery.getNextAttemptAt() != null
                && !delivery.getNextAttemptAt().isAfter(now);
    }

    static LocalDateTime nextRetry(int attempts, LocalDateTime failureTime) {
        return switch (attempts) {
            case 1 -> failureTime.plusMinutes(1);
            case 2 -> failureTime.plusMinutes(5);
            default -> null;
        };
    }

    static boolean exhausted(Delivery delivery) {
        return delivery.getState() == DeliveryState.RETRYABLE_REJECTION
                && delivery.getAttemptCount() >= AUTOMATIC_ATTEMPT_LIMIT
                && !delivery.isExtraAttemptPermitted();
    }

    static void requireRecoveryAction(Delivery delivery, RecoveryAction action, boolean acknowledgeDuplicateRisk) {
        boolean allowed = switch (action) {
            case FINALIZE -> delivery.getState() == DeliveryState.ACCEPTED;
            case CONFIRM_ACCEPTED, CONFIRM_RETRYABLE_REJECTION, CONFIRM_PERMANENT_REJECTION ->
                delivery.getState() == DeliveryState.UNKNOWN;
            case RETRY_ONCE -> exhausted(delivery);
            case REPLACE_UNKNOWN -> delivery.getState() == DeliveryState.UNKNOWN && acknowledgeDuplicateRisk;
        };
        if (!allowed) {
            throw new RecoveryConflictException();
        }
    }
}
