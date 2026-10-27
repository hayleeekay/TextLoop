package com.haylee.textloop.reminder.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RemindAgainRequest(
        @NotNull UUID operationId,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @JsonFormat(lenient = OptBoolean.FALSE) LocalDateTime scheduledAt) {
}
