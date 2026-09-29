package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;
import com.haylee.textloop.sms.SmsService;

@Service
public class ReminderService {
    private final ReminderRepository reminderRepository;
    private final SmsService smsService;

    public ReminderService(ReminderRepository reminderRepository, SmsService smsService) {
        this.reminderRepository = reminderRepository;
        this.smsService = smsService;
    }

    public ReminderResponse createReminder(CreateReminderRequest request) {
        Reminder reminder = new Reminder(
                request.message(),
                request.phoneNumber(),
                request.scheduledAt());

        Reminder savedReminder = reminderRepository.save(reminder);

        return toResponse(savedReminder);
    }

    public List<ReminderResponse> getReminders() {
        return reminderRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public List<Reminder> findDueReminders(LocalDateTime cutoff) {
        return reminderRepository.findByStatusAndScheduledAtLessThanEqual(
                ReminderStatus.PENDING,
                cutoff);
    }

    public void processDueReminders(LocalDateTime cutoff) {
        findDueReminders(cutoff).forEach(reminder -> {
            String message = "TextLoop Reminder:\n" + reminder.getMessage()
                    + "\n\nReply DONE, SNOOZE, or CANCEL.";
            smsService.sendSms(reminder.getPhoneNumber(), message);
            reminder.setStatus(ReminderStatus.SENT);
            reminderRepository.save(reminder);
        });
    }

    private ReminderResponse toResponse(Reminder reminder) {
        return new ReminderResponse(
                reminder.getId(),
                reminder.getMessage(),
                reminder.getScheduledAt(),
                reminder.getPhoneNumber(),
                reminder.getStatus());
    }
}
