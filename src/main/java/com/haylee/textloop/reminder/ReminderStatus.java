package com.haylee.textloop.reminder;

public enum ReminderStatus {
    /** Current acceptance is not finalized. Delivery state and withdrawal determine send eligibility. */
    PENDING,
    /** Current sender acceptance has been finalized, not necessarily delivered to a handset. */
    SENT
}
