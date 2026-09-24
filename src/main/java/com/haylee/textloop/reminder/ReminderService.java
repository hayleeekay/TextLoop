package com.haylee.textloop.reminder;

import org.springframework.stereotype.Service;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;

@Service
public class ReminderService {
    private final ReminderRepository reminderRepository;

    public ReminderService(ReminderRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
    }

    public ReminderResponse createReminder(CreateReminderRequest request) {
        Reminder reminder = new Reminder(
                request.message(),
                request.phoneNumber(),
                request.scheduledAt());

        Reminder savedReminder = reminderRepository.save(reminder);

        return new ReminderResponse(
                savedReminder.getId(),
                savedReminder.getMessage(),
                savedReminder.getScheduledAt(),
                savedReminder.getPhoneNumber(),
                savedReminder.getStatus());
    }
}
