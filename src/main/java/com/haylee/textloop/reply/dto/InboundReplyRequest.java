package com.haylee.textloop.reply.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public record InboundReplyRequest(
        @NotBlank String fromPhoneNumber,
        @NotBlank String body,
        UUID eventId) {

    public InboundReplyRequest(String fromPhoneNumber, String body) {
        this(fromPhoneNumber, body, null);
    }
}
