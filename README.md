# TextLoop

TextLoop is a reminder app built around text messages. The idea is simple: schedule
a text, get a reminder, and carry on. No completion checkbox or response required.
Snoozing by text reply is planned.

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

TextLoop is in development, with a working local web app and simulated sending.
It does not send real texts and is not ready for public use.

Currently implemented:

- Create reminders through the web app or API and store them in PostgreSQL
- Schedule from Messages, then edit or withdraw reminders from Schedule
- Check for due reminders every minute and simulate sender acceptance
- Browse sent and withdrawn reminders from the last seven days in History
- Use Reuse to bring a past reminder back into the composer and pick a new time
- Keep unresolved sends visible in Schedule, with bounded retries and recovery safeguards
- Run automated tests in GitHub Actions

Simulated reply receipt and a standalone command parser also exist, but replies do
not change reminders yet.

For endpoint contracts and local request examples, see the [API guide](docs/api-guide.md).

## Running Locally

To try the web UI with simulated sending, follow the [local setup guide](docs/local-setup.md).
It covers Java, PostgreSQL, starting the app, and a first reminder. No SMS account is needed.

## First Version Goal

Make it easy to schedule individual reminder texts and get another reminder when
needed, without maintaining a task list.

```text
schedule a reminder in the web app
    -> store it
    -> detect when it is due
    -> send the reminder (simulated for now)
```

## Architecture

The local interface uses plain HTML, CSS, and JavaScript served by Spring Boot.
The backend uses a feature-oriented structure:

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
    -> select due reminders with an eligible delivery
    -> commit UNKNOWN before submitting
    -> SmsService interface
    -> FakeSmsService
    -> record sender acceptance
    -> finalize reminder as SENT
```

Known retryable rejections have a bounded retry budget. Uncertain submissions remain
held for [local recovery](docs/delivery-recovery.md).

A real SMS implementation can later replace the fake sender without tying reminder
logic to a specific provider.

## Tech Stack

- Backend: Java 25 and Spring Boot
- Build tool: Maven
- Database: PostgreSQL
- Persistence: Spring Data JPA and Hibernate
- Frontend: HTML, CSS, and JavaScript served by Spring Boot
- SMS: simulated during development, then a real provider after the backend loop works
- Workflow: GitHub Issues, pull requests, and Java 25 tests in GitHub Actions

## Development Direction

The local scheduling and reuse flow is working. The next functional step is connecting
simulated replies to snooze, with clear targeting and duplicate-reply handling.

Later work includes:

- A more polished web interface
- Timezone-aware scheduling
- Versioned database migrations
- A real SMS provider
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
