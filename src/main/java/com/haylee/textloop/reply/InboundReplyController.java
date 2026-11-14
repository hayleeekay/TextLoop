package com.haylee.textloop.reply;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.haylee.textloop.reply.dto.InboundReplyRequest;
import com.haylee.textloop.reminder.SimulatedReply;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/inbound-replies")
public class InboundReplyController {
    private final InboundReplyService inboundReplyService;

    public InboundReplyController(InboundReplyService inboundReplyService) {
        this.inboundReplyService = inboundReplyService;
    }

    @PostMapping
    public ResponseEntity<SimulatedReply> receiveReply(@Valid @RequestBody InboundReplyRequest request) {
        if (request.eventId() == null) {
            inboundReplyService.receiveReply(request);
            return ResponseEntity.noContent().build();
        }
        try {
            return ResponseEntity.ok(inboundReplyService.simulateReply(request));
        } catch (DataAccessException | TransactionException failure) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Void> invalidReply() {
        return ResponseEntity.badRequest().build();
    }
}
