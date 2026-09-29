package com.haylee.textloop.scheduler;

import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.haylee.textloop.reminder.ReminderService;

class ReminderSchedulerTest {

    @Test
    void processesDueRemindersAtCurrentTime() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-29T16:00:00Z"),
                ZoneOffset.UTC);
        LocalDateTime expectedCutoff = LocalDateTime.of(2026, 9, 29, 16, 0);
        ReminderService reminderService = Mockito.mock(ReminderService.class);
        ReminderScheduler scheduler = new ReminderScheduler(reminderService, clock);

        scheduler.processDueReminders();

        verify(reminderService).processDueReminders(expectedCutoff);
    }
}
