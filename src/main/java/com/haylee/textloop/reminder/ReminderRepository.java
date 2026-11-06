package com.haylee.textloop.reminder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByStatusAndWithdrawnAtIsNullAndScheduledAtGreaterThanOrderByScheduledAtAscIdAsc(
            ReminderStatus status, LocalDateTime cutoff);

    List<Reminder> findByScheduledAtGreaterThanAndScheduledAtLessThanEqualOrderByScheduledAtDescIdDesc(
            LocalDateTime start, LocalDateTime end);

    @Query("select r from Reminder r where r.scheduledAt <= :cutoff "
            + "or r.withdrawnAt is not null or r.status <> 'PENDING' "
            + "order by r.scheduledAt desc, r.id desc")
    List<Reminder> findHistory(LocalDateTime cutoff);

    List<Reminder> findByStatusAndWithdrawnAtIsNullAndScheduledAtLessThanEqual(
            ReminderStatus status,
            LocalDateTime scheduledAt);

    @Query("select r.id from Reminder r where r.status = 'PENDING' "
            + "and r.withdrawnAt is null and r.currentDeliveryId is null")
    List<Long> findUntrackedPendingIds();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reminder r where r.id = :id")
    Optional<Reminder> findLockedById(Long id);
}
