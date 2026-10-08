package com.haylee.textloop.reminder;

/** A stale token or an action that does not apply to the current delivery. */
public class RecoveryConflictException extends IllegalStateException {
    public RecoveryConflictException() {
        super("Recovery refused: inspect the current delivery and use its latest version");
    }
}
