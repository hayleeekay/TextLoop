package com.haylee.textloop.reminder.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.haylee.textloop.reminder.ReminderLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record EditReminderRequest(
        @NotBlank @Pattern(regexp = ReminderLimits.NUL_FREE_TEXT)
        @Size(max = ReminderLimits.MESSAGE_MAX_LENGTH) String message,
        @NotNull @JsonFormat(lenient = OptBoolean.FALSE) LocalDateTime scheduledAt,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
