package com.haylee.textloop.reply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.haylee.textloop.reply.dto.InboundReplyRequest;

@Service
public class InboundReplyService {
    private static final Logger logger = LoggerFactory.getLogger(InboundReplyService.class);

    public void receiveReply(InboundReplyRequest request) {
        logger.info("Simulated inbound reply received");
    }
}
