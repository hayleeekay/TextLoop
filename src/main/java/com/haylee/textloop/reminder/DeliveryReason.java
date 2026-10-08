package com.haylee.textloop.reminder;

/** Controlled diagnostic codes. Never store sender exception text here. */
public enum DeliveryReason {
    NOT_SUBMITTED,
    SUBMISSION_UNCONFIRMED,
    ACCEPTANCE_CONFIRMED,
    RETRYABLE_NON_ACCEPTANCE,
    PERMANENT_NON_ACCEPTANCE,
    LEGACY_UNRECORDED
}
