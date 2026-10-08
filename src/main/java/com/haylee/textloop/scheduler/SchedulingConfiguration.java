package com.haylee.textloop.scheduler;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.haylee.textloop.reminder.ReminderService;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "textloop.recovery", havingValue = "false", matchIfMissing = true)
public class SchedulingConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(SchedulingConfiguration.class);

    @Bean
    @ConditionalOnProperty(name = "textloop.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    ReminderScheduler reminderScheduler(ReminderService service, Clock clock) {
        return new ReminderScheduler(service, clock);
    }

    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("reminder-scheduler-");
        // Recurring tasks already catch failures at this boundary. Report the failed run without payloads.
        scheduler.setErrorHandler(failure -> logger.error(
                "Reminder processing failed. Inspect delivery recovery state. category={}",
                failure instanceof DataAccessException || failure instanceof TransactionException
                        ? "DATABASE" : "UNEXPECTED"));
        return scheduler;
    }
}
