package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;
import com.haylee.textloop.sms.SmsService;
import com.haylee.textloop.sms.SmsSubmissionResult;
import java.util.UUID;
import java.util.Optional;

class ReminderServiceTest {
    private ReminderRepository reminderRepository;
    private SmsService smsService;
    private ReminderService reminderService;
    private DeliveryTransactions deliveries;
    private DeliveryRepository deliveryRepository;

    @BeforeEach
    void setUp() {
        reminderRepository = Mockito.mock(ReminderRepository.class);
        smsService = Mockito.mock(SmsService.class);
        deliveries = Mockito.mock(DeliveryTransactions.class);
        deliveryRepository = Mockito.mock(DeliveryRepository.class);
        reminderService = new ReminderService(reminderRepository, smsService, deliveryRepository, deliveries);
    }

    @Test
    void createsPendingReminder() {
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(1);

        CreateReminderRequest request = new CreateReminderRequest(
                "Take a walk",
                scheduledAt,
                "+12035550100");

        Mockito.when(deliveries.createReminder(request))
                .thenReturn(new Reminder(request.message(), request.phoneNumber(), request.scheduledAt()));

        ReminderResponse response = reminderService.createReminder(request);

        Assertions.assertEquals("Take a walk", response.message());
        Assertions.assertEquals(scheduledAt, response.scheduledAt());
        Assertions.assertEquals("+12035550100", response.phoneNumber());
        Assertions.assertEquals(ReminderStatus.PENDING, response.status());

        Mockito.verify(deliveries).createReminder(request);
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
    void recordsAcceptanceBeforeFinalization() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 10, 8, 12, 0);
        UUID id = UUID.randomUUID();
        Reminder reminder = new Reminder("Take a walk", "+12035550100", cutoff.minusMinutes(1));
        reminder.setCurrentDeliveryId(id);
        Mockito.when(reminderRepository.findByStatusAndScheduledAtLessThanEqual(ReminderStatus.PENDING, cutoff))
                .thenReturn(List.of(reminder));
        var submission = new DeliveryTransactions.Submission(id, 1, reminder.getPhoneNumber(), Delivery.renderMessage(reminder.getMessage()));
        Mockito.when(deliveries.claim(id, cutoff)).thenReturn(Optional.of(submission));
        var result = SmsSubmissionResult.accepted(null);
        Mockito.when(smsService.sendSms(id, submission.phoneNumber(), submission.message())).thenReturn(result);

        reminderService.processDueReminders(cutoff);

        InOrder order = Mockito.inOrder(deliveries, smsService);
        order.verify(deliveries).claim(id, cutoff);
        order.verify(smsService).sendSms(id, submission.phoneNumber(), submission.message());
        order.verify(deliveries).recordResult(id, 1, result);
        order.verify(deliveries).finalizeAccepted(id);
    }

    @Test
    void unexpectedSenderExceptionPropagatesWithoutRecordingRejection() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 10, 8, 12, 0);
        UUID id = UUID.randomUUID();
        Reminder reminder = new Reminder("Take a walk", "+12035550100", cutoff.minusMinutes(1));
        reminder.setCurrentDeliveryId(id);
        Mockito.when(reminderRepository.findByStatusAndScheduledAtLessThanEqual(ReminderStatus.PENDING, cutoff))
                .thenReturn(List.of(reminder));
        var submission = new DeliveryTransactions.Submission(id, 1, reminder.getPhoneNumber(), Delivery.renderMessage(reminder.getMessage()));
        Mockito.when(deliveries.claim(id, cutoff)).thenReturn(Optional.of(submission));
        RuntimeException failure = new IllegalStateException("Unclassified sender failure");
        Mockito.when(smsService.sendSms(id, submission.phoneNumber(), submission.message())).thenThrow(failure);

        Assertions.assertSame(failure, Assertions.assertThrows(RuntimeException.class,
                () -> reminderService.processDueReminders(cutoff)));
        Mockito.verify(deliveries, Mockito.never()).recordResult(Mockito.any(), Mockito.anyInt(), Mockito.any());
        Mockito.verify(deliveries, Mockito.never()).finalizeAccepted(Mockito.any());
    }
}
