package com.haylee.textloop.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;

import com.haylee.textloop.reminder.ReminderService;

public class ReminderScheduler {

    private final ReminderService reminderService;
    private final Clock clock;

    public ReminderScheduler(ReminderService reminderService, Clock clock) {
        this.reminderService = reminderService;
        this.clock = clock;
    }

    @Scheduled(fixedRateString = "${textloop.reminders.check-interval-ms:60000}")
    public void processDueReminders() {
        LocalDateTime cutoff = LocalDateTime.now(clock);
        reminderService.processDueReminders(cutoff);
    }
}
