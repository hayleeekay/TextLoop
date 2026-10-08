package com.haylee.textloop.reminder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {
    @Query("select d.reminder.id from Delivery d where d.id = :id")
    Optional<Long> findReminderId(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> findLockedById(UUID id);

    @Query("select d.id from Delivery d where d.state = 'ACCEPTED' "
            + "and d.reminder.status = 'PENDING' and d.reminder.currentDeliveryId = d.id")
    List<UUID> findAwaitingFinalization();

    List<Delivery> findAllByOrderByCreatedAtAscIdAsc();
}
