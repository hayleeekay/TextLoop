package com.haylee.textloop.reminder;

/** Fixed reasons only; rejected values must not enter error responses or logs. */
public class ReminderMutationException extends RuntimeException {
    public enum Reason {
        NOT_FOUND, STALE_VERSION, NOT_EDITABLE, INVALID_SCHEDULE,
        NOT_REUSABLE, DELIVERY_REVIEW_REQUIRED, OPERATION_CONFLICT, SAVE_FAILED
    }

    private final Reason reason;

    ReminderMutationException(Reason reason) {
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
