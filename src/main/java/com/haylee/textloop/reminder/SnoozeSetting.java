package com.haylee.textloop.reminder;

import java.util.Map;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "snooze_settings")
class SnoozeSetting {
    static final int DEFAULT_MINUTES = 30;
    private static final Map<Integer, String> DURATIONS = Map.of(
            15, "15 minutes", 30, "30 minutes", 60, "1 hour", 120, "2 hours",
            1440, "1 day", 2880, "2 days", 10080, "1 week");

    @Id
    private Integer id;
    private int minutes;

    protected SnoozeSetting() {}

    SnoozeSetting(int minutes) {
        this.id = 1;
        this.minutes = minutes;
    }

    int minutes() {
        return minutes;
    }

    static String durationText(int minutes) {
        return DURATIONS.get(minutes);
    }
}
