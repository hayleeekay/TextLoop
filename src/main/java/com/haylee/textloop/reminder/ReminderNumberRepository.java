package com.haylee.textloop.reminder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ReminderNumberRepository extends JpaRepository<ReminderNumber, Integer> {
    @Modifying
    @Query(value = """
            insert into reminder_number (id, phone_number) values (1, :phoneNumber)
            on conflict (id) do update set phone_number = excluded.phone_number
            """, nativeQuery = true)
    void setNumber(String phoneNumber);
}
