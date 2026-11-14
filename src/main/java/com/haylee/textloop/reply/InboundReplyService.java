package com.haylee.textloop.reply;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.haylee.textloop.reminder.DeliveryTransactions;
import com.haylee.textloop.reminder.SimulatedReply;
import com.haylee.textloop.reply.dto.InboundReplyRequest;

@Service
public class InboundReplyService {
    private static final Logger logger = LoggerFactory.getLogger(InboundReplyService.class);
    private final DeliveryTransactions transactions;
    private final ReplyParser parser = new ReplyParser();

    public InboundReplyService(DeliveryTransactions transactions) {
        this.transactions = transactions;
    }

    public void receiveReply(InboundReplyRequest request) {
        logger.info("Simulated inbound reply received");
    }

    public SimulatedReply simulateReply(InboundReplyRequest request) {
        String phoneNumber = request.fromPhoneNumber().strip().replaceAll("[ ().-]", "");
        if (!phoneNumber.matches("\\+[1-9][0-9]{1,14}")) {
            return new SimulatedReply(null, null, false);
        }
        String body = request.body().strip().toUpperCase(Locale.ROOT);
        ReplyCommand command = parser.parse(body);
        boolean offerHint = command == ReplyCommand.UNKNOWN
                && !body.matches("STOP|STOPALL|UNSUBSCRIBE|CANCEL|END|QUIT|HELP|INFO|START|YES|UNSTOP");
        return transactions.applySimulatedReply(request.eventId(), phoneNumber, command, offerHint);
    }
}
