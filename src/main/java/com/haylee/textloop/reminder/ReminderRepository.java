package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByStatusAndScheduledAtLessThanEqual(
            ReminderStatus status,
            LocalDateTime scheduledAt);
}
