package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByStatusAndScheduledAtLessThanEqual(
            ReminderStatus status,
            LocalDateTime scheduledAt);

    @Query("select r.id from Reminder r where r.status = 'PENDING' and r.currentDeliveryId is null")
    List<Long> findUntrackedPendingIds();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reminder r where r.id = :id")
    Optional<Reminder> findLockedById(Long id);
}
