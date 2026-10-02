package com.haylee.textloop.reply;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.haylee.textloop.reply.dto.InboundReplyRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/inbound-replies")
public class InboundReplyController {
    private final InboundReplyService inboundReplyService;

    public InboundReplyController(InboundReplyService inboundReplyService) {
        this.inboundReplyService = inboundReplyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receiveReply(@Valid @RequestBody InboundReplyRequest request) {
        inboundReplyService.receiveReply(request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Void> invalidReply() {
        return ResponseEntity.badRequest().build();
    }
}
