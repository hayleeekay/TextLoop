package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;
import com.haylee.textloop.sms.SmsService;

class ReminderServiceTest {
    private ReminderRepository reminderRepository;
    private SmsService smsService;
    private ReminderService reminderService;

    @BeforeEach
    void setUp() {
        reminderRepository = Mockito.mock(ReminderRepository.class);
        smsService = Mockito.mock(SmsService.class);
        reminderService = new ReminderService(reminderRepository, smsService);
    }

    @Test
    void createsPendingReminder() {
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(1);

        CreateReminderRequest request = new CreateReminderRequest(
                "Take a walk",
                scheduledAt,
                "+12035550100");

        Mockito.when(reminderRepository.save(Mockito.any(Reminder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReminderResponse response = reminderService.createReminder(request);

        Assertions.assertEquals("Take a walk", response.message());
        Assertions.assertEquals(scheduledAt, response.scheduledAt());
        Assertions.assertEquals("+12035550100", response.phoneNumber());
        Assertions.assertEquals(ReminderStatus.PENDING, response.status());

        ArgumentCaptor<Reminder> reminderCaptor = ArgumentCaptor.forClass(Reminder.class);
        Mockito.verify(reminderRepository).save(reminderCaptor.capture());

        Reminder reminderToSave = reminderCaptor.getValue();
        Assertions.assertEquals("Take a walk", reminderToSave.getMessage());
        Assertions.assertEquals(scheduledAt, reminderToSave.getScheduledAt());
        Assertions.assertEquals("+12035550100", reminderToSave.getPhoneNumber());
        Assertions.assertEquals(ReminderStatus.PENDING, reminderToSave.getStatus());
    }

    @Test
    void getsAllRemindersAsResponses() {
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(1);
        Reminder reminder = Mockito.mock(Reminder.class);

        Mockito.when(reminder.getId()).thenReturn(1L);
        Mockito.when(reminder.getMessage()).thenReturn("Take a walk");
        Mockito.when(reminder.getScheduledAt()).thenReturn(scheduledAt);
        Mockito.when(reminder.getPhoneNumber()).thenReturn("+12035550100");
        Mockito.when(reminder.getStatus()).thenReturn(ReminderStatus.PENDING);
        Mockito.when(reminderRepository.findAll()).thenReturn(List.of(reminder));

        List<ReminderResponse> responses = reminderService.getReminders();

        Assertions.assertEquals(
                List.of(new ReminderResponse(
                        1L,
                        "Take a walk",
                        scheduledAt,
                        "+12035550100",
                        ReminderStatus.PENDING)),
                responses);
        Mockito.verify(reminderRepository).findAll();
    }

    @Test
    void sendsDueReminderThroughSmsService() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 9, 29, 16, 0);
        Reminder reminder = new Reminder(
                "Take a walk",
                "+12035550100",
                cutoff.minusMinutes(1));
        Mockito.when(reminderRepository.findByStatusAndScheduledAtLessThanEqual(
                        ReminderStatus.PENDING, cutoff))
                .thenReturn(List.of(reminder));

        reminderService.processDueReminders(cutoff);

        Mockito.verify(smsService).sendSms(
                "+12035550100",
                "TextLoop Reminder:\nTake a walk\n\nReply DONE, SNOOZE, or CANCEL.");
        Assertions.assertEquals(ReminderStatus.PENDING, reminder.getStatus());
    }
}
