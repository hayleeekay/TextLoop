package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.haylee.textloop.PostgreSQLTestConfiguration;

@SpringBootTest
@Import(PostgreSQLTestConfiguration.class)
class ReminderDueSelectionIntegrationTest {

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private ReminderService reminderService;

    @BeforeEach
    void clearReminders() {
        reminderRepository.deleteAll();
    }

    @Test
    void findsOnlyPendingRemindersScheduledAtOrBeforeCutoff() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 9, 29, 12, 0);

        Reminder overdue = reminderRepository.save(new Reminder(
                "Overdue reminder",
                "+12035550100",
                cutoff.minusMinutes(1)));
        Reminder dueAtCutoff = reminderRepository.save(new Reminder(
                "Due at cutoff",
                "+12035550101",
                cutoff));
        reminderRepository.save(new Reminder(
                "Future reminder",
                "+12035550102",
                cutoff.plusMinutes(1)));

        Reminder sentReminder = new Reminder(
                "Already sent",
                "+12035550103",
                cutoff.minusMinutes(1));
        sentReminder.setStatus(ReminderStatus.SENT);
        reminderRepository.save(sentReminder);

        List<Reminder> dueReminders = reminderService.findDueReminders(cutoff);

        assertThat(dueReminders)
                .extracting(Reminder::getId)
                .containsExactlyInAnyOrder(overdue.getId(), dueAtCutoff.getId());
        assertThat(dueReminders)
                .extracting(Reminder::getStatus)
                .containsOnly(ReminderStatus.PENDING);
    }
}
