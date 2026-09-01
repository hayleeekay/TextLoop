# TextLoop

TextLoop is a text-first reminder app. Users create reminders in a simple app,
TextLoop sends them by SMS, and users can reply with commands such as `DONE`,
`SNOOZE`, or `CANCEL` to update the reminder.

Development starts with simulated SMS so the complete reminder engine can be built
and tested locally before adding a paid provider or production messaging setup.

## Why TextLoop

Ordinary reminder notifications are easy to clear and forget. Text messages tend to
remain visible and demand a response. TextLoop turns that behavior into a reminder
workflow: create a reminder, receive it as a text, and act on it by replying.

The idea came from a personal workaround: normal reminders often disappeared into a
crowded notification list, while an unread text stayed visible until it was handled.
I eventually started asking friends to use scheduled iMessages when something really
needed my attention. TextLoop turns that workaround into a backend-focused app.

## Current Status

TextLoop is in early backend development.

Implemented:

- Spring Boot application with `GET /health`
- Local PostgreSQL connection
- JPA/Hibernate `Reminder` entity and `reminders` table
- `PENDING` and `SENT` reminder statuses
- Spring Data `ReminderRepository`
- Application-context smoke test

Next:

- Create reminders through `POST /api/reminders`
- Retrieve reminders through `GET /api/reminders`
- Detect due reminders and simulate sending them

There is no reminder HTTP API, scheduler, SMS integration, reply handling, or user
interface yet.

## Local MVP

The first complete local version will support this flow:

```text
create reminder
    -> view reminder
    -> detect when due
    -> fake-send SMS
    -> mark SENT
    -> simulate reply
    -> handle DONE / CANCEL / SNOOZE
    -> view reminder history
```

Real SMS, deployment, authentication, and UI polish come after the local loop works.

## Architecture

TextLoop uses a feature-oriented, layered Spring Boot architecture:

```text
HTTP request
    -> Controller and request/response DTOs
    -> Service and business rules
    -> Spring Data repository
    -> Hibernate / JPA
    -> PostgreSQL
```

Background reminder delivery will follow:

```text
Scheduler
    -> Reminder service
    -> SmsService interface
    -> FakeSmsService locally
    -> real provider implementation later
```

Planned packages:

- `health` — application health endpoint
- `reminder` — reminder model, repository, service, controller, and DTOs
- `scheduler` — due-reminder background work
- `sms` — provider-independent SMS interface and implementations
- `reply` — inbound reply parsing and handling
- `event` — reminder lifecycle history
- `common` — only genuinely shared configuration and error handling

## Tech Stack

- Java 21
- Spring Boot 3.5
- Maven
- Spring Web
- Spring Data JPA / Hibernate
- PostgreSQL
- JUnit and Spring Test

## Run Locally

### Prerequisites

- JDK 21
- PostgreSQL 16 or another compatible local PostgreSQL version

Create a local database named `textloop`:

```bash
createdb textloop
```

Provide local database credentials without committing them:

```bash
export DB_USERNAME="your_postgres_username"
export DB_PASSWORD="your_postgres_password"
```

If the local PostgreSQL user does not require a password, an empty value is valid:

```bash
export DB_PASSWORD=""
```

Start the application:

```bash
./mvnw spring-boot:run
```

Verify it from another terminal:

```bash
curl http://localhost:8080/health
```

Expected response:

```text
TextLoop is running
```

Run the current tests with PostgreSQL running and the same environment variables set:

```bash
./mvnw test
```

## Database Development

Hibernate currently uses `ddl-auto=update` to create and update the local schema from
JPA entities. This is a local-development convenience. Versioned migrations, likely
Flyway, are required before deployment or use with important data.

## Roadmap

### v0.1 — Local MVP

- Create and view reminders
- Detect due reminders
- Simulate SMS sending
- Handle simulated `DONE`, `CANCEL`, and `SNOOZE` replies
- Record and view reminder history
- Protect core behavior with automated tests

### v0.2 — Private Beta

- Add a minimal usable interface
- Add versioned database migrations
- Add timezone-aware scheduling
- Improve isolated integration testing and CI
- Prepare production configuration and operational safeguards

### v1.0 — Public Launch

- Select and integrate a real SMS provider
- Complete current cost, consent, privacy, security, and messaging-requirement reviews
- Add monitoring, abuse prevention, data-retention rules, and launch documentation

## Current Constraints

- Keep infrastructure and service costs below $20/month where practical.
- Use fake SMS before paying for or depending on a real provider.
- Local scheduling currently uses `LocalDateTime` without timezone support.
- Phone number is a temporary local identity; there are no accounts yet.
- This is not production-ready and should not handle real users or sensitive data yet.

## Future Ideas

- Recurring reminders
- Habit check-ins by text
- Daily affirmations and motivational messages
- Natural-language reply parsing
- AI-assisted reminder management
- Dashboard and history analytics
- Better handling for multiple active reminders
