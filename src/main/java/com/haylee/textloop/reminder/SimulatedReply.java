package com.haylee.textloop.reminder;

import java.time.LocalDateTime;

/** Text the simulator would show, with no outbound SMS side effect. */
public record SimulatedReply(String replyText, LocalDateTime scheduledAt, boolean replayed) {}
