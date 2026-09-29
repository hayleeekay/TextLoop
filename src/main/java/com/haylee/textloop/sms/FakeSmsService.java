package com.haylee.textloop.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FakeSmsService implements SmsService {
    private static final Logger logger = LoggerFactory.getLogger(FakeSmsService.class);

    @Override
    public void sendSms(String phoneNumber, String message) {
        logger.info("[FAKE SMS]\nTo: {}\nMessage: {}", phoneNumber, message);
    }
}
