package com.haylee.textloop.reminder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record WithdrawReminderRequest(@NotNull @PositiveOrZero Long expectedVersion) {
}
