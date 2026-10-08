package com.haylee.textloop.reminder;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
@ConditionalOnProperty(name = "textloop.recovery", havingValue = "true")
public class DeliveryRecoveryCommand implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger logger = LoggerFactory.getLogger(DeliveryRecoveryCommand.class);
    private final DeliveryTransactions transactions;
    private int exitCode;

    public DeliveryRecoveryCommand(DeliveryTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        Command command;
        try {
            String action = option(arguments, "recovery.action", "list");
            if (action.equals("list")) {
                command = null;
            } else {
                if (!option(arguments, "recovery.app-stopped", "false").equals("true")) {
                    throw new IllegalArgumentException();
                }
                RecoveryAction recoveryAction = switch (action) {
                    case "finalize" -> RecoveryAction.FINALIZE;
                    case "confirm-accepted" -> RecoveryAction.CONFIRM_ACCEPTED;
                    case "confirm-retryable-rejection" -> RecoveryAction.CONFIRM_RETRYABLE_REJECTION;
                    case "confirm-permanent-rejection" -> RecoveryAction.CONFIRM_PERMANENT_REJECTION;
                    case "retry-once" -> RecoveryAction.RETRY_ONCE;
                    case "replace-unknown" -> RecoveryAction.REPLACE_UNKNOWN;
                    default -> throw new IllegalArgumentException();
                };
                command = new Command(UUID.fromString(option(arguments, "recovery.delivery", "")),
                        Long.parseLong(option(arguments, "recovery.version", "")), recoveryAction,
                        option(arguments, "recovery.ack", "").equals("duplicate-risk"));
            }
        } catch (IllegalArgumentException failure) {
            exitCode = 2;
            logger.error("Recovery refused: invalid options or missing stopped-application acknowledgement.");
            return;
        }
        if (command == null) {
            transactions.inspect().forEach(row -> logger.info("Delivery inspection: {}", row));
            return;
        }
        try {
            UUID resultingId = transactions.recover(command.id(), command.version(), command.action(), command.acknowledgeRisk());
            logger.info("Recovery committed: action={} delivery={}", command.action(), resultingId);
        } catch (RecoveryConflictException failure) {
            exitCode = 2;
            logger.error("Recovery refused: stale version, inactive intent, or inapplicable action. List before retrying.");
        }
    }

    private static String option(ApplicationArguments arguments, String name, String fallback) {
        var values = arguments.getOptionValues(name);
        if (values == null) {
            return fallback;
        }
        if (values.size() != 1) {
            throw new IllegalArgumentException();
        }
        return values.getFirst();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private record Command(UUID id, long version, RecoveryAction action, boolean acknowledgeRisk) {}
}
