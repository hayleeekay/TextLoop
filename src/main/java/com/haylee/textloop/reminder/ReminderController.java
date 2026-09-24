package com.haylee.textloop.reminder;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {
    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReminderResponse createReminder(
            @Valid @RequestBody CreateReminderRequest request) {
        return reminderService.createReminder(request);
    }
}
