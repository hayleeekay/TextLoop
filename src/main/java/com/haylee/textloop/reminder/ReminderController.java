package com.haylee.textloop.reminder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.EditReminderRequest;
import com.haylee.textloop.reminder.dto.RemindAgainRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;
import com.haylee.textloop.reminder.dto.ReminderViewResponse;
import com.haylee.textloop.reminder.dto.WithdrawReminderRequest;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {
    private final ReminderService reminderService;
    private final ReminderReadService reminderReadService;

    public ReminderController(ReminderService reminderService, ReminderReadService reminderReadService) {
        this.reminderService = reminderService;
        this.reminderReadService = reminderReadService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReminderResponse createReminder(
            @Valid @RequestBody CreateReminderRequest request) {
        return reminderService.createReminder(request);
    }

    @GetMapping
    public List<ReminderResponse> getReminders() {
        return reminderService.getReminders();
    }

    @GetMapping("/upcoming")
    public List<ReminderViewResponse> upcoming() {
        return reminderReadService.upcoming();
    }

    @GetMapping("/recent")
    public List<ReminderViewResponse> recent() {
        return reminderReadService.recent();
    }

    @GetMapping("/{id}")
    public ReminderViewResponse detail(@PathVariable Long id) {
        return reminderReadService.detail(id);
    }

    @PutMapping("/{id}")
    public ReminderResponse editReminder(@PathVariable Long id, @Valid @RequestBody EditReminderRequest request) {
        return reminderService.editReminder(id, request);
    }

    @PostMapping("/{id}/withdraw")
    public ReminderResponse withdrawReminder(@PathVariable Long id, @Valid @RequestBody WithdrawReminderRequest request) {
        return reminderService.withdrawReminder(id, request.expectedVersion());
    }

    @PostMapping("/{id}/remind-again")
    public ReminderResponse remindAgain(@PathVariable Long id, @Valid @RequestBody RemindAgainRequest request) {
        return reminderService.remindAgain(id, request);
    }

    @ExceptionHandler(ReminderMutationException.class)
    public ResponseEntity<Map<String, String>> refusedMutation(ReminderMutationException failure) {
        HttpStatus status = switch (failure.getReason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_SCHEDULE -> HttpStatus.BAD_REQUEST;
            case STALE_VERSION, NOT_EDITABLE, NOT_REUSABLE, DELIVERY_REVIEW_REQUIRED, OPERATION_CONFLICT -> HttpStatus.CONFLICT;
            case SAVE_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of("code", failure.getReason().name()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalidReminderId() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_ID"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public void invalidReminder(HttpServletResponse response) throws IOException {
        // Keep the default error response without logging rejected field values.
        response.sendError(HttpStatus.BAD_REQUEST.value());
    }
}
