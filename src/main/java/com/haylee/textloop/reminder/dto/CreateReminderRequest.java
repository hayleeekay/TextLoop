package com.haylee.textloop.reminder.dto;

import java.time.LocalDateTime;

import com.haylee.textloop.reminder.ReminderLimits;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateReminderRequest(
        @NotBlank @Size(max = ReminderLimits.MESSAGE_MAX_LENGTH) String message,
        @NotNull @Future LocalDateTime scheduledAt,
        @NotBlank @Size(max = ReminderLimits.PHONE_NUMBER_MAX_LENGTH) String phoneNumber) {
}
