package com.haylee.textloop.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.haylee.textloop.PostgreSQLTestConfiguration;
import com.haylee.textloop.reminder.dto.CreateReminderRequest;
import com.haylee.textloop.sms.SmsService;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({PostgreSQLTestConfiguration.class, ReminderInputIntegrationTest.Fixtures.class})
@ExtendWith(OutputCaptureExtension.class)
class ReminderInputIntegrationTest {
    private static final String BASE = "/api/reminders";
    private static final String MESSAGE = "private-input-message";
    private static final String PHONE = "+12035550100";
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired ReminderRepository reminders;
    @Autowired DeliveryRepository deliveries;
    @Autowired DeliveryTransactions transactions;
    @Autowired ReminderService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean SmsService sender;

    @BeforeEach
    void reset() {
        reminders.deleteAll();
        clock.instant = Instant.parse("2001-01-01T12:00:00.123456789Z");
    }

    @AfterEach
    void noSubmission() { verifyNoInteractions(sender); }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private String future() { return now().plusDays(1).toString(); }
    private record Target(String path, HttpMethod method, Long id, long version) {}

    private Target target(String action) {
        if (action.equals("create")) return new Target(BASE, HttpMethod.POST, null, 0);
        var created = service.createReminder(new CreateReminderRequest(MESSAGE, now().plusDays(2), PHONE));
        if (action.equals("reuse")) {
            var withdrawn = service.withdrawReminder(created.id(), created.version());
            return new Target(BASE + "/" + created.id() + "/remind-again", HttpMethod.POST,
                    created.id(), withdrawn.version());
        }
        return new Target(BASE + "/" + created.id(), HttpMethod.PUT, created.id(), created.version());
    }

    private String body(Target target, String time) {
        if (target.id() == null) {
            return json.writeValueAsString(Map.of("scheduledAt", time, "message", MESSAGE, "phoneNumber", PHONE));
        }
        return target.path().endsWith("remind-again")
                ? json.writeValueAsString(Map.of("scheduledAt", time, "expectedVersion", target.version(),
                        "operationId", UUID.randomUUID()))
                : json.writeValueAsString(Map.of("scheduledAt", time, "message", MESSAGE, "expectedVersion", target.version()));
    }

    private ResponseEntity<String> request(Target target, String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(target.path(), target.method(), new HttpEntity<>(body, headers), String.class);
    }

    private List<?> rows() {
        return List.of(jdbc.queryForList("select * from reminders order by id"),
                jdbc.queryForList("select * from deliveries order by id"),
                jdbc.queryForList("select * from reminder_reuses order by id"));
    }

    private void privateBadRequest(ResponseEntity<String> response, CapturedOutput output) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContain(MESSAGE, PHONE, "private-time-marker", "trace", "exception", "errors");
        assertThat(output).doesNotContain(MESSAGE, PHONE, "private-time-marker");
    }

    static Stream<Arguments> nulInputs() {
        return Stream.of("\0", "\0tail", "head\0", "head\0tail", "head\0\0tail")
                .flatMap(value -> Stream.of(Arguments.of("create", "message", value),
                        Arguments.of("create", "phoneNumber", value), Arguments.of("edit", "message", value)));
    }

    @ParameterizedTest
    @MethodSource("nulInputs")
    void decodedNulIsRejectedBeforeAnyWrite(String action, String field, String value, CapturedOutput output) {
        var target = target(action);
        ObjectNode payload = (ObjectNode) json.readTree(body(target, future()));
        payload.put(field, value);
        String raw = json.writeValueAsString(payload);
        assertThat(raw).contains("\\u0000");
        var before = rows();
        privateBadRequest(request(target, raw), output);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void unescapedJsonNulIsAlsoRejected(CapturedOutput output) {
        var target = target("create");
        var before = rows();
        privateBadRequest(request(target, body(target, future()).replace(MESSAGE, "head\0tail")), output);
        assertThat(rows()).isEqualTo(before);
    }

    static Stream<Arguments> invalidDateStrings() {
        return Stream.of("create", "edit", "reuse").flatMap(action -> Stream.of(
                "2001-01-02T12:00:00Z", "2001-01-02T12:00:00z", "2001-01-02T12:00:00.123456789Z",
                "2001-01-02T12:00:00+00:00", "2001-01-02T12:00:00-04:00",
                "2001-01-02T12:00:00+0000", "2001-01-02T12:00:00+01:02:03",
                "2001-01-02T12:00:00[UTC]", "2001-02-30T12:00:00", "private-time-marker")
                .map(time -> Arguments.of(action, time)));
    }

    @ParameterizedTest
    @MethodSource("invalidDateStrings")
    void invalidLocalDateStringsArePrivateAndSaveNothing(String action, String time, CapturedOutput output) {
        var target = target(action);
        var before = rows();
        var response = request(target, body(target, time));
        privateBadRequest(response, output);
        assertThat(response.getBody()).doesNotContain(time);
        assertThat(rows()).isEqualTo(before);
    }

    static Stream<Arguments> localDateStrings() {
        return Stream.of("create", "edit", "reuse").flatMap(action -> Stream.of(
                "2001-01-02T12:30", "2001-01-02T12:30:00", "2001-01-02T12:30:01.1",
                "2001-01-02T12:30:00.123456", "2001-01-02T12:30:00.123456789",
                "2001-01-02T12:30:59.999999999", "2001-01-02T12:30:00.000000999")
                .map(time -> Arguments.of(action, time)));
    }

    @ParameterizedTest
    @MethodSource("localDateStrings")
    void canonicalTimeAgreesAcrossMutationReadsAndDatabase(String action, String time) {
        var target = target(action);
        var response = request(target, body(target, time));
        assertThat(response.getStatusCode()).isEqualTo(action.equals("create") ? HttpStatus.CREATED : HttpStatus.OK);
        JsonNode result = json.readTree(response.getBody());
        long id = result.path("id").asLong();
        LocalDateTime canonical = LocalDateTime.parse(time).truncatedTo(ChronoUnit.MICROS);
        assertThat(LocalDateTime.parse(result.path("scheduledAt").asText())).isEqualTo(canonical);
        assertThat(jdbc.queryForObject("select scheduled_at from reminders where id = ?", LocalDateTime.class, id))
                .isEqualTo(canonical);
        assertThat(json.readTree(http.getForObject(BASE + "/" + id, String.class)).path("scheduledAt"))
                .isEqualTo(result.path("scheduledAt"));
        for (String path : List.of(BASE, BASE + "/upcoming")) {
            var selected = json.readTree(http.getForObject(path, String.class)).valueStream()
                    .filter(row -> row.path("id").asLong() == id).findFirst().orElseThrow();
            assertThat(selected.path("scheduledAt")).isEqualTo(result.path("scheduledAt"));
        }
        if (action.equals("reuse")) {
            assertThat(jdbc.queryForObject("select scheduled_at from reminder_reuses where result_reminder_id = ?",
                    LocalDateTime.class, id)).isEqualTo(canonical);
        }
        clock.instant = canonical.plusSeconds(1).toInstant(ZoneOffset.UTC);
        assertThat(json.readTree(http.getForObject(BASE + "/recent", String.class)).valueStream()
                .filter(row -> row.path("id").asLong() == id).findFirst().orElseThrow().path("scheduledAt"))
                .isEqualTo(result.path("scheduledAt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "edit", "reuse"})
    void canonicalValueMustBeStrictlyAfterFullClock(String action, CapturedOutput output) {
        var target = target(action);
        var before = rows();
        for (LocalDateTime time : List.of(now().minusDays(1), now(), now().plusNanos(1),
                now().truncatedTo(ChronoUnit.MICROS))) {
            var response = request(target, body(target, time.toString()));
            privateBadRequest(response, output);
            assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("INVALID_SCHEDULE");
            assertThat(rows()).isEqualTo(before);
        }
        var nextMicro = now().truncatedTo(ChronoUnit.MICROS).plusNanos(1000);
        assertThat(request(target, body(target, nextMicro.toString())).getStatusCode())
                .isEqualTo(action.equals("create") ? HttpStatus.CREATED : HttpStatus.OK);
    }

    @ParameterizedTest
    @CsvSource({"edit,false", "edit,true", "reuse,false", "reuse,true"})
    void futureValidationOccursAfterReminderAndDeliveryLockWaits(String action, boolean holdDelivery) throws Exception {
        var target = target(action);
        String payload = body(target, now().plusMinutes(1).toString());
        var before = rows();
        var pending = new TransactionTemplate(transactionManager).execute(status -> {
            if (holdDelivery) {
                deliveries.findLockedById(reminders.findById(target.id()).orElseThrow().getCurrentDeliveryId()).orElseThrow();
            } else {
                reminders.findLockedById(target.id()).orElseThrow();
            }
            var response = CompletableFuture.supplyAsync(() -> request(target, payload));
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                    Integer.class) >= 1);
            clock.instant = clock.instant.plus(Duration.ofMinutes(1));
            return response;
        });
        var response = pending.get(10, TimeUnit.SECONDS);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("INVALID_SCHEDULE");
        assertThat(rows()).isEqualTo(before);
    }

    static Stream<Arguments> acceptedText() {
        return Stream.of(
                Arguments.of("  private-input-message\r\n\tCafé e\u0301 中文 😀\u0001 \\u0000  ",
                        " \t+1 (203) 555-0100\r\n☎😀 "),
                Arguments.of("m".repeat(1000), "p".repeat(255)),
                Arguments.of("😀".repeat(500), "😀".repeat(127) + "p"));
    }

    @ParameterizedTest
    @MethodSource("acceptedText")
    void acceptedTextSurvivesCreateEditReuseAndReadsExactly(String message, String phone) {
        var target = target("create");
        var payload = json.writeValueAsString(Map.of("message", message, "phoneNumber", phone, "scheduledAt", future()));
        var result = json.readTree(request(target, payload).getBody());
        long id = result.path("id").asLong();
        assertPayload(result, message, phone);
        var edit = new Target(BASE + "/" + id, HttpMethod.PUT, id, result.path("version").asLong());
        String editedMessage = new StringBuilder(message).reverse().toString();
        var edited = json.readTree(request(edit, json.writeValueAsString(Map.of("message", editedMessage,
                "scheduledAt", future(), "expectedVersion", edit.version()))).getBody());
        assertPayload(edited, editedMessage, phone);
        var withdrawn = service.withdrawReminder(id, edited.path("version").asLong());
        var reuse = new Target(BASE + "/" + id + "/remind-again", HttpMethod.POST, id, withdrawn.version());
        var reused = json.readTree(request(reuse, body(reuse, future())).getBody());
        assertPayload(reused, editedMessage, phone);
        assertThat(jdbc.queryForList("select message from deliveries where reminder_id = ? order by created_at, id", String.class, id))
                .containsExactlyInAnyOrder("TextLoop Reminder:\n" + message, "TextLoop Reminder:\n" + editedMessage);
    }

    private void assertPayload(JsonNode response, String message, String phone) {
        long id = response.path("id").asLong();
        assertThat(response.path("message").asText()).isEqualTo(message);
        assertThat(response.path("phoneNumber").asText()).isEqualTo(phone);
        var stored = reminders.findById(id).orElseThrow();
        assertThat(stored.getMessage()).isEqualTo(message);
        assertThat(stored.getPhoneNumber()).isEqualTo(phone);
        assertThat(deliveries.findById(stored.getCurrentDeliveryId()).orElseThrow().getMessage())
                .isEqualTo("TextLoop Reminder:\n" + message);
        var detail = json.readTree(http.getForObject(BASE + "/" + id, String.class));
        assertThat(detail.path("message").asText()).isEqualTo(message);
        assertThat(detail.path("phoneNumber").asText()).isEqualTo(phone);
        var listed = json.readTree(http.getForObject(BASE, String.class)).valueStream()
                .filter(row -> row.path("id").asLong() == id).findFirst().orElseThrow();
        assertThat(listed.path("message").asText()).isEqualTo(message);
        assertThat(listed.path("phoneNumber").asText()).isEqualTo(phone);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary MutableClock inputClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2001-01-01T12:00:00.123456789Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
