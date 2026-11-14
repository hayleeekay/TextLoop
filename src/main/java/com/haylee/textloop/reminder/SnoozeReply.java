package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "snooze_replies")
class SnoozeReply {
    @Id
    private UUID id;
    @Column(length = 16)
    private String phoneNumber;
    private String replyText;
    private LocalDateTime scheduledAt;

    protected SnoozeReply() {}

    SnoozeReply(UUID id, String phoneNumber, String replyText, LocalDateTime scheduledAt) {
        this.id = id;
        this.phoneNumber = phoneNumber;
        this.replyText = replyText;
        this.scheduledAt = scheduledAt;
    }

    boolean isFrom(String phoneNumber) {
        return phoneNumber.equals(this.phoneNumber);
    }

    SimulatedReply result(boolean replayed) {
        return new SimulatedReply(replyText, scheduledAt, replayed);
    }
}
