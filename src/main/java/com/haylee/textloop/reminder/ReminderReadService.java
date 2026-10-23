package com.haylee.textloop.reminder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.haylee.textloop.reminder.dto.ReminderViewResponse;
import com.haylee.textloop.reminder.dto.ReminderViewResponse.Feedback;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReminderReadService {
    private final ReminderRepository reminders;
    private final DeliveryRepository deliveries;
    private final Clock clock;

    public ReminderReadService(ReminderRepository reminders, DeliveryRepository deliveries, Clock clock) {
        this.reminders = reminders;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    public List<ReminderViewResponse> upcoming() {
        return views(reminders.findByStatusAndWithdrawnAtIsNullAndScheduledAtGreaterThanOrderByScheduledAtAscIdAsc(
                ReminderStatus.PENDING, cutoff()));
    }

    public List<ReminderViewResponse> recent() {
        LocalDateTime end = cutoff();
        return views(reminders.findByScheduledAtGreaterThanAndScheduledAtLessThanEqualOrderByScheduledAtDescIdDesc(
                end.minusDays(7), end));
    }

    public ReminderViewResponse detail(Long id) {
        Reminder reminder = reminders.findById(id)
                .orElseThrow(() -> new ReminderMutationException(ReminderMutationException.Reason.NOT_FOUND));
        return views(List.of(reminder)).getFirst();
    }

    private LocalDateTime cutoff() {
        // Stored timestamps have microsecond precision. Flooring preserves > and <= comparisons.
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private List<ReminderViewResponse> views(List<Reminder> selected) {
        List<UUID> ids = selected.stream().map(Reminder::getCurrentDeliveryId).filter(id -> id != null).distinct().toList();
        // Both reads share one snapshot, even if a claim commits between them.
        Map<UUID, Delivery> current = deliveries.findAllById(ids).stream()
                .collect(Collectors.toMap(Delivery::getId, Function.identity()));
        return selected.stream().map(reminder -> view(reminder, current.get(reminder.getCurrentDeliveryId()))).toList();
    }

    private ReminderViewResponse view(Reminder reminder, Delivery delivery) {
        Feedback feedback = feedback(reminder, delivery);
        boolean editable = feedback == Feedback.WAITING && DeliveryPolicy.editable(delivery);
        boolean reusable = feedback == Feedback.LEGACY_SENT
                || (delivery != null && feedback != Feedback.INCONSISTENT_DELIVERY && DeliveryPolicy.reusable(delivery));
        return new ReminderViewResponse(reminder.getId(), reminder.getMessage(), reminder.getScheduledAt(),
                reminder.getPhoneNumber(), reminder.getStatus(), reminder.getVersion(), reminder.getWithdrawnAt(),
                editable, editable, reusable, feedback,
                feedback == Feedback.RETRY_WAITING ? delivery.getNextAttemptAt() : null);
    }

    private Feedback feedback(Reminder reminder, Delivery delivery) {
        if (reminder.getCurrentDeliveryId() == null) {
            if (reminder.getWithdrawnAt() != null) return Feedback.INCONSISTENT_DELIVERY;
            return reminder.getStatus() == ReminderStatus.SENT ? Feedback.LEGACY_SENT : Feedback.UNTRACKED;
        }
        if (delivery == null || !delivery.getReminder().getId().equals(reminder.getId())) {
            return Feedback.INCONSISTENT_DELIVERY;
        }
        if (reminder.getWithdrawnAt() != null) {
            return reminder.getStatus() == ReminderStatus.PENDING && DeliveryPolicy.editable(delivery)
                    ? Feedback.WITHDRAWN : Feedback.INCONSISTENT_DELIVERY;
        }
        if (reminder.getStatus() == ReminderStatus.SENT) {
            return delivery.getState() == DeliveryState.ACCEPTED ? Feedback.ACCEPTED : Feedback.INCONSISTENT_DELIVERY;
        }
        return switch (delivery.getState()) {
            case READY -> DeliveryPolicy.editable(delivery) ? Feedback.WAITING : Feedback.INCONSISTENT_DELIVERY;
            case ACCEPTED -> Feedback.ACCEPTED;
            case UNKNOWN -> Feedback.DELIVERY_UNCERTAIN;
            case PERMANENT_REJECTION -> Feedback.PERMANENT_REJECTION;
            case RETRYABLE_REJECTION -> DeliveryPolicy.exhausted(delivery) ? Feedback.RETRY_EXHAUSTED
                    : delivery.getNextAttemptAt() != null ? Feedback.RETRY_WAITING : Feedback.INCONSISTENT_DELIVERY;
        };
    }
}
