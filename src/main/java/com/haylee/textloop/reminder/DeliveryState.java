package com.haylee.textloop.reminder;

/** Submission outcome, separate from the reminder lifecycle. */
public enum DeliveryState {
    /** No submission has begun for this intent. */
    READY,
    /** Unresolved during a live call; still unresolved after interruption. Never auto-retry. */
    UNKNOWN,
    /** Sender acceptance has committed. Reminder finalization may still be outstanding. */
    ACCEPTED,
    /** Known non-acceptance; eligible only within the persisted retry policy. */
    RETRYABLE_REJECTION,
    /** Known non-acceptance; unchanged submissions are held. */
    PERMANENT_REJECTION
}
