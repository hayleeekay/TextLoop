package com.haylee.textloop.reminder.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RemindAgainRequest(
        @NotNull UUID operationId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull LocalDateTime scheduledAt) {
}
