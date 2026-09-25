package com.haylee.textloop.reminder;

import java.util.List;

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

        return toResponse(savedReminder);
    }

    public List<ReminderResponse> getReminders() {
        return reminderRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
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
