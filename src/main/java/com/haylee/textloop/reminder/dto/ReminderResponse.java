package com.haylee.textloop.reminder.dto;

import java.time.LocalDateTime;

import com.haylee.textloop.reminder.ReminderStatus;

public record ReminderResponse(
        Long id,
        String message,
        LocalDateTime scheduledAt,
        String phoneNumber,
        ReminderStatus status) {
}
