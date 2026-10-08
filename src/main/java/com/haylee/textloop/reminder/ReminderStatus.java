package com.haylee.textloop.reminder;

public enum ReminderStatus {
    /** Lifecycle work is unfinished. Delivery state separately determines send eligibility. */
    PENDING,
    /** Current sender acceptance has been finalized, not necessarily delivered to a handset. */
    SENT
}
