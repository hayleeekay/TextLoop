package com.haylee.textloop.reminder;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderReuseRepository extends JpaRepository<ReminderReuse, Long> {
    Optional<ReminderReuse> findBySourceReminderIdAndOperationId(Long sourceId, UUID operationId);
}
