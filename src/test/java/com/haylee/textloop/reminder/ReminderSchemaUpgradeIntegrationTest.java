package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;

import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.reminder.dto.ReminderResponse;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.hibernate.ddl-auto=update")
@Import(ReminderSchemaUpgradeIntegrationTest.LegacyDatabaseConfiguration.class)
@DirtiesContext
class ReminderSchemaUpgradeIntegrationTest {
    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void widensExistingMessageColumnWithoutChangingSavedReminder() {
        assertThat(columnLength("message")).isEqualTo(1000);
        assertThat(columnLength("phone_number")).isEqualTo(255);
        assertThat(reminderRepository.count()).isEqualTo(1);
        assertLegacyReminderUnchanged();

        String message = "m".repeat(1000);
        LocalDateTime scheduledAt = LocalDateTime.now().plusDays(1).withNano(0);
        ResponseEntity<ReminderResponse> response = restTemplate.postForEntity(
                "/api/reminders",
                new CreateReminderRequest(message, scheduledAt, "+12035550101"),
                ReminderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(message);
        Reminder saved = reminderRepository.findById(response.getBody().id()).orElseThrow();
        assertThat(saved.getMessage()).isEqualTo(message);
        assertThat(saved.getPhoneNumber()).isEqualTo("+12035550101");
        assertThat(saved.getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(saved.getStatus()).isEqualTo(ReminderStatus.PENDING);
        assertThat(reminderRepository.count()).isEqualTo(2);
        assertLegacyReminderUnchanged();
    }

    private Integer columnLength(String column) {
        return jdbcTemplate.queryForObject("""
                select character_maximum_length from information_schema.columns
                where table_schema = 'public' and table_name = 'reminders' and column_name = ?
                """, Integer.class, column);
    }

    private void assertLegacyReminderUnchanged() {
        Reminder saved = reminderRepository.findById(1L).orElseThrow();
        assertThat(saved.getMessage()).isEqualTo("m".repeat(255));
        assertThat(saved.getPhoneNumber()).isEqualTo("p".repeat(255));
        assertThat(saved.getScheduledAt()).isEqualTo(LocalDateTime.of(2000, 1, 2, 10, 0));
        assertThat(saved.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2000, 1, 1, 10, 0));
        assertThat(saved.getUpdatedAt()).isEqualTo(LocalDateTime.of(2000, 1, 2, 10, 1));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LegacyDatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("reminder-legacy-schema.sql");
        }
    }
}
