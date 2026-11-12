package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** A successful reuse binds a source-scoped operation to its request and resulting reminder. */
@Entity
@Table(name = "reminder_reuses", uniqueConstraints = @UniqueConstraint(
        name = "reminder_reuse_operation", columnNames = {"source_reminder_id", "operation_id"}))
public class ReminderReuse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "source_reminder_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Reminder sourceReminder;

    @Column(nullable = false, updatable = false)
    private UUID operationId;

    @Column(nullable = false, updatable = false)
    private long expectedVersion;

    @Column(nullable = false, updatable = false)
    private LocalDateTime scheduledAt;

    @Column(length = ReminderLimits.MESSAGE_MAX_LENGTH, updatable = false)
    private String requestedMessage;

    @ManyToOne(optional = false)
    @JoinColumn(name = "result_reminder_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Reminder resultReminder;

    protected ReminderReuse() {}

    ReminderReuse(Reminder source, UUID operationId, long expectedVersion, LocalDateTime scheduledAt,
            String requestedMessage, Reminder result) {
        this.sourceReminder = source;
        this.operationId = operationId;
        this.expectedVersion = expectedVersion;
        this.scheduledAt = scheduledAt;
        this.requestedMessage = requestedMessage;
        this.resultReminder = result;
    }

    boolean matches(long expectedVersion, LocalDateTime scheduledAt, String requestedMessage) {
        return this.expectedVersion == expectedVersion && this.scheduledAt.equals(scheduledAt)
                && Objects.equals(this.requestedMessage, requestedMessage);
    }

    Reminder getResultReminder() {
        return resultReminder;
    }
}
