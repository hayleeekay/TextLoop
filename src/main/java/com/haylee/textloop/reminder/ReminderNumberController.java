package com.haylee.textloop.reminder;

import java.io.IOException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletResponse;
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
    DeliveryTransactions.NumberSetting get() {
        return transactions.numberSetting();
    }

    @PutMapping
    DeliveryTransactions.NumberSetting update(@Valid @RequestBody NumberRequest setting) {
        try {
            transactions.setReminderNumber(setting.phoneNumber());
        } catch (DataAccessException | TransactionException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new DeliveryTransactions.NumberSetting(setting.phoneNumber(), true);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    void invalidNumber(HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.BAD_REQUEST.value());
    }

    record NumberRequest(@NotNull @Pattern(regexp = "\\+[1-9][0-9]{1,14}") String phoneNumber) {}
}
