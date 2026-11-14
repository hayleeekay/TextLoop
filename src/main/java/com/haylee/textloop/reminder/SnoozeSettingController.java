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

@RestController
@RequestMapping("/api/settings/snooze-duration")
class SnoozeSettingController {
    private final DeliveryTransactions transactions;

    SnoozeSettingController(DeliveryTransactions transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    DurationSetting get() {
        return new DurationSetting(transactions.snoozeMinutes());
    }

    @PutMapping
    DurationSetting update(@RequestBody DurationSetting setting) {
        Integer minutes = setting.minutes();
        if (minutes == null || SnoozeSetting.durationText(minutes) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            transactions.setSnoozeMinutes(minutes);
        } catch (DataAccessException | TransactionException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return setting;
    }

    record DurationSetting(Integer minutes) {}
}
