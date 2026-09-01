# TextLoop

TextLoop is an SMS-based reminder system where users create reminders in a web app
and receive them via text message. Users can reply with actions like `DONE`, `SNOOZE`,
or `CANCEL`, and the backend updates the reminder's status and history.

## Problem

Do you ever set a reminder on your phone only for it to show up along with all the
rest of your notifications, then you just clear all your notifications and ignore it?
Even if I make it repeat daily for a month, I will likely ignore it the entire time.
Whether this is because of my ADHD or not, I'm not sure. You know what I don't ignore
though? Texts. What I love is when I have an appointment and the business sends me a
text reminder. Then I can leave it unread for days, but I'm constantly wondering what
that notification is so I keep checking it, and I never forget it. At some point, I
even started asking friends to use the `Send Later` iMessage feature for reminders,
which is where this idea came from. Bottom line: I'm far more likely to consider a
text than a traditional notification.

## Status

TextLoop is in early development and is not a usable reminder app yet. The current
code is the beginning of the backend foundation, not a release intended for people to
install or rely on.

Currently implemented:

- Spring Boot application with `GET /health`
- PostgreSQL connection and basic database configuration
- JPA/Hibernate `Reminder` entity and `reminders` table
- `PENDING` and `SENT` reminder statuses
- Spring Data `ReminderRepository`
- Application startup smoke test

Next development steps:

- Create reminders through `POST /api/reminders`
- Retrieve reminders through `GET /api/reminders`
- Detect when reminders are due
- Simulate sending reminders before connecting a real SMS provider

## Version 1 Goal

Build a complete SMS reminder system that can create, send, update, snooze, cancel,
and track reminders through text-based interaction.

The planned experience is:

```text
create reminder in a web app
    -> store reminder
    -> detect when it is due
    -> send it by SMS
    -> receive DONE / CANCEL / SNOOZE replies
    -> update status and history
```

During development, SMS will be simulated so the reminder engine can be built and
tested before adding provider cost, setup, or production messaging requirements.

## Planned Architecture

The backend is being built as a feature-oriented Spring Boot application:

```text
HTTP request
    -> Controller and request/response DTOs
    -> Service and business rules
    -> Spring Data repository
    -> Hibernate / JPA
    -> PostgreSQL
```

Reminder delivery is planned to follow:

```text
Scheduler
    -> Reminder service
    -> SmsService interface
    -> simulated SMS during development
    -> real provider implementation later
```

Planned packages:

- `health`: application health endpoint
- `reminder`: reminder model, repository, service, controller, and DTOs
- `scheduler`: due-reminder background work
- `sms`: provider-independent SMS interface and implementations
- `reply`: inbound reply parsing and handling
- `event`: reminder lifecycle history
- `common`: genuinely shared configuration and error handling

## Tech Stack

- Backend: Java 21 and Spring Boot
- Build tool: Maven
- Database: PostgreSQL
- Persistence: Spring Data JPA and Hibernate
- Frontend: a minimal web UI later
- SMS: simulated during development, then a real provider after the backend loop works
- Workflow: GitHub Issues, branches, pull requests, and automated checks as the project grows

## Development Direction

The current backend milestone is intended to prove the complete reminder loop with
simulated SMS and replies. It is a development milestone, not a user release.

Later work will include:

- A minimal interface
- A real SMS provider
- Timezone-aware scheduling
- Versioned database migrations
- More complete automated testing
- Deployment and operational safeguards
- Privacy, security, cost, consent, and messaging-requirement reviews before public use

## Constraints

- Keep infrastructure and service costs below $20/month where practical.
- Prioritize simple, maintainable architecture.
- Do not pay for or depend on real SMS before the reminder engine works.
- Do not treat development shortcuts as production-ready decisions.
- There is no user authentication yet.
- Phone number is only a temporary identity during early development.

## Future Ideas

- Daily affirmations and motivational texts curated to the user
- Recurring reminders
- Natural-language reply parsing
- Habit tracking with text check-ins
- AI-assisted reminder and habit management
- Dashboard and history analytics
- Better handling for multiple active reminders
- User accounts and authentication
