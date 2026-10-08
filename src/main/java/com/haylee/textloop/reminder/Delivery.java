package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "deliveries")
public class Delivery {
    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "reminder_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Reminder reminder;

    @Column(nullable = false, updatable = false, length = ReminderLimits.PHONE_NUMBER_MAX_LENGTH)
    private String phoneNumber;

    // Includes the reminder wrapper, so the raw-input limit is not the storage limit.
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryState state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryReason reason;

    @Column(nullable = false)
    private int attemptCount;
    private LocalDateTime nextAttemptAt;
    @Column(nullable = false)
    private boolean extraAttemptPermitted;
    @Column(length = 255)
    private String acceptanceReference;
    private UUID replacesDeliveryId;
    @Enumerated(EnumType.STRING)
    private RecoveryAction recoveryAction;
    private LocalDateTime recoveredAt;
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    @Version
    private long version;

    protected Delivery() {
    }

    Delivery(Reminder reminder, LocalDateTime now, boolean legacyUnknown) {
        this.id = UUID.randomUUID();
        this.reminder = reminder;
        this.phoneNumber = reminder.getPhoneNumber();
        this.message = renderMessage(reminder.getMessage());
        this.state = legacyUnknown ? DeliveryState.UNKNOWN : DeliveryState.READY;
        this.reason = legacyUnknown ? DeliveryReason.LEGACY_UNRECORDED : DeliveryReason.NOT_SUBMITTED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static String renderMessage(String raw) {
        return "TextLoop Reminder:\n" + raw + "\n\nReply DONE, SNOOZE, or CANCEL.";
    }

    void beginAttempt(LocalDateTime now) {
        state = DeliveryState.UNKNOWN;
        reason = DeliveryReason.SUBMISSION_UNCONFIRMED;
        attemptCount++;
        nextAttemptAt = null;
        extraAttemptPermitted = false;
        updatedAt = now;
    }

    void recordOutcome(DeliveryState state, DeliveryReason reason, String reference,
            LocalDateTime retryAt, LocalDateTime now) {
        this.state = state;
        this.reason = reason;
        this.acceptanceReference = reference;
        this.nextAttemptAt = retryAt;
        this.updatedAt = now;
    }

    void recover(RecoveryAction action, LocalDateTime now) {
        recoveryAction = action;
        recoveredAt = now;
        updatedAt = now;
    }

    void permitExtraAttempt(LocalDateTime now) {
        extraAttemptPermitted = true;
        nextAttemptAt = now;
    }

    void replace(Delivery previous) {
        replacesDeliveryId = previous.id;
        // An intentional replacement repeats the original snapshot, not a mutable reminder.
        phoneNumber = previous.phoneNumber;
        message = previous.message;
    }

    public UUID getId() {
        return id;
    }

    public Reminder getReminder() {
        return reminder;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public String getMessage() {
        return message;
    }

    public DeliveryState getState() {
        return state;
    }

    public DeliveryReason getReason() {
        return reason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public boolean isExtraAttemptPermitted() {
        return extraAttemptPermitted;
    }

    public String getAcceptanceReference() {
        return acceptanceReference;
    }

    public UUID getReplacesDeliveryId() {
        return replacesDeliveryId;
    }

    public RecoveryAction getRecoveryAction() {
        return recoveryAction;
    }

    public LocalDateTime getRecoveredAt() {
        return recoveredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
