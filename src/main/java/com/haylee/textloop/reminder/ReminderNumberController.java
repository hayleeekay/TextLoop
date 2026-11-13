package com.haylee.textloop.reminder;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/settings/reminder-number")
class ReminderNumberController {
    private final DeliveryTransactions transactions;

    ReminderNumberController(DeliveryTransactions transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    NumberSetting get() {
        return new NumberSetting(transactions.reminderNumber());
    }

    @PutMapping
    NumberSetting update(@Valid @RequestBody NumberSetting setting) {
        try {
            transactions.setReminderNumber(setting.phoneNumber());
        } catch (DataAccessException | TransactionException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return setting;
    }

    record NumberSetting(@NotNull @Pattern(regexp = "\\+[1-9][0-9]{1,14}") String phoneNumber) {}
}
