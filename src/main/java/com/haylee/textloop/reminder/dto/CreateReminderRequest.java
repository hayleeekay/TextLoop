package com.haylee.textloop.reminder.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateReminderRequest(
        @NotBlank String message,
        @NotNull @Future LocalDateTime scheduledAt,
        @NotBlank String phoneNumber) {
}
