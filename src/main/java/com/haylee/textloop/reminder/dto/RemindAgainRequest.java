package com.haylee.textloop.reminder.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.haylee.textloop.reminder.ReminderLimits;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RemindAgainRequest(
        @NotNull UUID operationId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @JsonFormat(lenient = OptBoolean.FALSE) LocalDateTime scheduledAt,
        @Size(max = ReminderLimits.MESSAGE_MAX_LENGTH)
        @Pattern(regexp = "(?=.*\\P{javaWhitespace})" + ReminderLimits.NUL_FREE_TEXT,
                flags = Pattern.Flag.DOTALL) String message) {
    public RemindAgainRequest(UUID operationId, Long expectedVersion, LocalDateTime scheduledAt) {
        this(operationId, expectedVersion, scheduledAt, null);
    }
}
