package com.haylee.textloop.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.haylee.textloop.reminder.Reminder;
import com.haylee.textloop.reminder.ReminderService;

@ExtendWith(OutputCaptureExtension.class)
class ReminderSchedulerTest {

    @Test
    void checksAtCurrentTimeAndLogsDueReminders(CapturedOutput output) {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-29T16:00:00Z"),
                ZoneOffset.UTC);
        LocalDateTime expectedCutoff = LocalDateTime.of(2026, 9, 29, 16, 0);
        ReminderService reminderService = Mockito.mock(ReminderService.class);
        Reminder dueReminder = Mockito.mock(Reminder.class);

        when(dueReminder.getId()).thenReturn(42L);
        when(dueReminder.getMessage()).thenReturn("Take a walk");
        when(reminderService.findDueReminders(expectedCutoff))
                .thenReturn(List.of(dueReminder));

        ReminderScheduler scheduler = new ReminderScheduler(reminderService, clock);

        scheduler.detectDueReminders();

        verify(reminderService).findDueReminders(expectedCutoff);
        verify(dueReminder, never()).setStatus(any());
        assertThat(output)
                .contains("Due reminder found")
                .contains("id=42")
                .contains("message=Take a walk");
    }
}
