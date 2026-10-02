package com.haylee.textloop.reply.dto;

import jakarta.validation.constraints.NotBlank;

public record InboundReplyRequest(
        @NotBlank String fromPhoneNumber,
        @NotBlank String body) {
}
