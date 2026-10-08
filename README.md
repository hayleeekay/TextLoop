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

TextLoop is still in early development and is not ready to use yet. The backend can
now complete its first one-way reminder flow with simulated SMS, but it is still
development code rather than a release.

Currently implemented:

- Create and retrieve reminders through the API
- Store reminders in PostgreSQL as `PENDING` or `SENT`
- Detect when pending reminders are due
- Produce a simulated SMS and mark successful sends as `SENT`
- Run automated tests in GitHub Actions

Next development steps:

- Simulate incoming SMS replies
- Parse and match `DONE`, `CANCEL`, and `SNOOZE`
- Update reminder state and history

For endpoint contracts and local request examples, see the [API guide](docs/api-guide.md).

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

## Architecture

The backend uses a feature-oriented Spring Boot structure:

```text
HTTP request
    -> Controller and request/response DTOs
    -> Service and business rules
    -> Spring Data repository
    -> Hibernate / JPA
    -> PostgreSQL
```

Reminder delivery currently follows:

```text
Scheduler
    -> Reminder service
    -> find due PENDING reminders
    -> SmsService interface
    -> FakeSmsService
    -> save reminder as SENT
```

A real SMS implementation can later replace the fake sender without tying reminder
logic to a specific provider.

## Tech Stack

- Backend: Java 25 and Spring Boot
- Build tool: Maven
- Database: PostgreSQL
- Persistence: Spring Data JPA and Hibernate
- Frontend: a minimal web UI later
- SMS: simulated during development, then a real provider after the backend loop works
- Workflow: GitHub Issues, pull requests, and Java 25 tests in GitHub Actions

## Development Direction

The first one-way backend milestone is complete: reminders can be created, stored,
detected when due, fake-sent, and marked as `SENT`.

The next phase is simulated inbound reply handling. Reply behavior will be built and
tested locally before connecting a real SMS provider. TextLoop remains a development
project rather than a user release.

Later work will include:

- Reminder reply handling and lifecycle history
- A minimal interface
- A real SMS provider
- Timezone-aware scheduling
- Versioned database migrations
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
