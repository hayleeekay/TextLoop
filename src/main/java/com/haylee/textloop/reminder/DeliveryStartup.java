package com.haylee.textloop.reminder;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class DeliveryStartup implements ApplicationRunner {
    private final DeliveryTransactions transactions;

    public DeliveryStartup(DeliveryTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        transactions.initializeLegacy();
    }
}
