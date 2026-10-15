package com.haylee.textloop.reply;

import java.util.Locale;

public class ReplyParser {
    public ReplyCommand parse(String body) {
        if (body == null) {
            return ReplyCommand.UNKNOWN;
        }

        return switch (body.strip().toUpperCase(Locale.ROOT)) {
            case "DONE" -> ReplyCommand.DONE;
            case "CANCEL" -> ReplyCommand.CANCEL;
            case "SNOOZE" -> ReplyCommand.SNOOZE;
            default -> ReplyCommand.UNKNOWN;
        };
    }
}
