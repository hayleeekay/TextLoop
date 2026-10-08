package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.haylee.textloop.PostgreSQLTestConfiguration;
import com.haylee.textloop.sms.SmsService;
import com.haylee.textloop.sms.SmsSubmissionResult;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;

@SpringBootTest
@Import(PostgreSQLTestConfiguration.class)
class ReminderDueSelectionIntegrationTest {

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private ReminderService reminderService;

    @MockitoBean
    private SmsService smsService;

    @BeforeEach
    void clearReminders() {
        reminderRepository.deleteAll();
        Mockito.when(smsService.sendSms(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(SmsSubmissionResult.accepted(null));
    }

    @Test
    void findsOnlyPendingRemindersScheduledAtOrBeforeCutoff() {
        LocalDateTime cutoff = LocalDateTime.now().plusDays(1).withNano(0);

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

    @Test
    void persistsSentStatusAndDoesNotSendAgainOnLaterPass() {
        LocalDateTime cutoff = LocalDateTime.now().plusDays(1).withNano(0);
        var response = reminderService.createReminder(new CreateReminderRequest(
                "Take a walk", cutoff.minusMinutes(1), "+12035550100"));
        Reminder reminder = reminderRepository.findById(response.id()).orElseThrow();

        reminderService.processDueReminders(cutoff);

        Reminder savedReminder = reminderRepository.findById(reminder.getId()).orElseThrow();
        assertThat(savedReminder.getStatus()).isEqualTo(ReminderStatus.SENT);

        reminderService.processDueReminders(cutoff.plusMinutes(1));

        Mockito.verify(smsService).sendSms(
                reminder.getCurrentDeliveryId(), "+12035550100",
                "TextLoop Reminder:\nTake a walk\n\nReply DONE, SNOOZE, or CANCEL.");
        Mockito.verifyNoMoreInteractions(smsService);
    }
}
