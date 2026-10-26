package com.haylee.textloop.reminder.dto;

import java.time.LocalDateTime;

import com.haylee.textloop.reminder.ReminderStatus;

/** Current alert information. Action flags are advisory; saves recheck eligibility under locks. */
public record ReminderViewResponse(
        Long id,
        String message,
        LocalDateTime scheduledAt,
        String phoneNumber,
        ReminderStatus status,
        long version,
        LocalDateTime withdrawnAt,
        boolean canEdit,
        boolean canWithdraw,
        boolean canRemindAgain,
        Feedback feedback,
        LocalDateTime nextAttemptAt) {

    /** Describes submission, never task completion or confirmed handset delivery. */
    public enum Feedback {
        WAITING,
        ACCEPTED,
        WITHDRAWN,
        RETRY_WAITING,
        RETRY_EXHAUSTED,
        PERMANENT_REJECTION,
        DELIVERY_UNCERTAIN,
        /** Recorded as SENT without a delivery record; acceptance evidence is unavailable. */
        LEGACY_SENT,
        UNTRACKED,
        INCONSISTENT_DELIVERY
    }
}
