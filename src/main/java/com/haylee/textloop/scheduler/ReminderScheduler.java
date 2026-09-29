package com.haylee.textloop.scheduler;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.haylee.textloop.reminder.ReminderService;

@Component
public class ReminderScheduler {
    private static final long CHECK_INTERVAL_MILLISECONDS = 60_000;

    private final ReminderService reminderService;
    private final Clock clock;

    public ReminderScheduler(ReminderService reminderService, Clock clock) {
        this.reminderService = reminderService;
        this.clock = clock;
    }

    @Scheduled(fixedRate = CHECK_INTERVAL_MILLISECONDS)
    public void processDueReminders() {
        LocalDateTime cutoff = LocalDateTime.now(clock);
        reminderService.processDueReminders(cutoff);
    }
}
