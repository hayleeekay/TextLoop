package com.haylee.textloop.reminder;

import org.springframework.boot.ExitCodeGenerator;

/** A nonzero recovery result without retaining untrusted exception text or nested causes. */
public class RecoveryFailureException extends RuntimeException implements ExitCodeGenerator {
    public static final int EXIT_CODE = 1;

    public RecoveryFailureException() {
        super("Recovery startup or execution failed");
    }

    @Override
    public int getExitCode() {
        return EXIT_CODE;
    }
}
