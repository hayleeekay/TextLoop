package com.haylee.textloop.sms;

import java.util.UUID;

public interface SmsService {
    /**
     * One submission attempt. Acceptance is not evidence of handset delivery.
     * Adapters translate documented provider outcomes; uncertain failures never imply safe retry.
     * Unexpected integration and programming errors must propagate.
     */
    SmsSubmissionResult sendSms(UUID deliveryId, String phoneNumber, String message);
}
