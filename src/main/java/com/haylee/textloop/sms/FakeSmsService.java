package com.haylee.textloop.sms;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FakeSmsService implements SmsService {
    private static final Logger logger = LoggerFactory.getLogger(FakeSmsService.class);

    @Override
    public SmsSubmissionResult sendSms(UUID deliveryId, String phoneNumber, String message) {
        logger.info("[FAKE SMS] Simulated acceptance: delivery={}", deliveryId);
        return SmsSubmissionResult.accepted(null);
    }
}
