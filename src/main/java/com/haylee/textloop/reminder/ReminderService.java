package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;
import com.haylee.textloop.sms.SmsService;
import com.haylee.textloop.sms.SmsSubmissionResult;

@Service
public class ReminderService {
    private static final Logger logger = LoggerFactory.getLogger(ReminderService.class);
    private final ReminderRepository reminderRepository;
    private final SmsService smsService;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryTransactions deliveries;

    public ReminderService(ReminderRepository reminderRepository, SmsService smsService,
            DeliveryRepository deliveryRepository, DeliveryTransactions deliveries) {
        this.reminderRepository = reminderRepository;
        this.smsService = smsService;
        this.deliveryRepository = deliveryRepository;
        this.deliveries = deliveries;
    }

    public ReminderResponse createReminder(CreateReminderRequest request) {
        return toResponse(deliveries.createReminder(request));
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

    @Transactional(propagation = Propagation.NEVER)
    public void processDueReminders(LocalDateTime cutoff) {
        // Scheduling may start before startup runners. Adoption also guards any untracked pending rows.
        deliveries.initializeLegacy();
        for (var id : deliveryRepository.findAwaitingFinalization()) {
            deliveries.finalizeAccepted(id);
        }
        for (Reminder reminder : findDueReminders(cutoff).stream()
                .sorted(Comparator.comparing(Reminder::getScheduledAt).thenComparing(Reminder::getId)).toList()) {
            var submission = deliveries.claim(reminder.getCurrentDeliveryId(), cutoff);
            if (submission.isEmpty()) {
                continue;
            }
            var attempt = submission.get();
            var result = smsService.sendSms(attempt.deliveryId(), attempt.phoneNumber(), attempt.message());
            deliveries.recordResult(attempt.deliveryId(), attempt.attemptCount(), result);
            logger.atLevel(result.outcome() == SmsSubmissionResult.Outcome.ACCEPTED
                    ? Level.INFO : Level.WARN)
                    .log("Submission outcome: delivery={} attempt={} outcome={}",
                            attempt.deliveryId(), attempt.attemptCount(), result.outcome());
            if (result.outcome() == SmsSubmissionResult.Outcome.ACCEPTED) {
                deliveries.finalizeAccepted(attempt.deliveryId());
            }
        }
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
