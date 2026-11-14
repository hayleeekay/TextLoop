package com.haylee.textloop.reminder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reminder_number")
class ReminderNumber {
    @Id
    private Integer id;

    @Column(nullable = false, length = 16)
    private String phoneNumber;

    protected ReminderNumber() {}

    String phoneNumber() {
        return phoneNumber;
    }
}
