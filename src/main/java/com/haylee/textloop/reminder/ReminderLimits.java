package com.haylee.textloop.reminder;

/** Input limits measured in UTF-16 code units, matching String.length(). */
public final class ReminderLimits {
    public static final int MESSAGE_MAX_LENGTH = 1000;
    public static final int PHONE_NUMBER_MAX_LENGTH = 255;

    private ReminderLimits() {
    }
}
