package com.haylee.textloop.sms;

import java.util.Objects;

/** A sender's outcome, before TextLoop durably records it. No provider payloads or error text. */
public record SmsSubmissionResult(Outcome outcome, String acceptanceReference) {
    public enum Outcome {
        ACCEPTED,
        /** Known non-acceptance, with a temporary cause. */
        RETRYABLE_REJECTION,
        /** Known non-acceptance; repeating the unchanged request is inappropriate. */
        PERMANENT_REJECTION,
        /** Acceptance cannot be established or ruled out. */
        UNKNOWN
    }

    public SmsSubmissionResult {
        Objects.requireNonNull(outcome);
        if (acceptanceReference != null
                && (outcome != Outcome.ACCEPTED || acceptanceReference.length() > 255)) {
            throw new IllegalArgumentException("Invalid acceptance reference");
        }
    }

    public static SmsSubmissionResult accepted(String reference) {
        return new SmsSubmissionResult(Outcome.ACCEPTED, reference);
    }

    public static SmsSubmissionResult retryableRejection() {
        return new SmsSubmissionResult(Outcome.RETRYABLE_REJECTION, null);
    }

    public static SmsSubmissionResult permanentRejection() {
        return new SmsSubmissionResult(Outcome.PERMANENT_REJECTION, null);
    }

    public static SmsSubmissionResult unknown() {
        return new SmsSubmissionResult(Outcome.UNKNOWN, null);
    }
}
