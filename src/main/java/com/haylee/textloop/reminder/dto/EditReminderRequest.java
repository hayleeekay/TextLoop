package com.haylee.textloop.reminder.dto;

import java.time.LocalDateTime;

import com.haylee.textloop.reminder.ReminderLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record EditReminderRequest(
        @NotBlank @Size(max = ReminderLimits.MESSAGE_MAX_LENGTH) String message,
        @NotNull LocalDateTime scheduledAt,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
