# TextLoop API

TextLoop's development API supports creating, editing, withdrawing, and retrieving
reminders, simulating inbound replies, and checking whether the application responds.

These examples assume the backend is running locally at `http://localhost:8080`.
Adjust the URL if you use another port. There is currently no authentication or
hosted API.

| Action | Endpoint | Successful response |
| --- | --- | --- |
| [Create a reminder](#create-a-reminder) | `POST /api/reminders` | `201 Created` |
| [Edit a reminder](#edit-a-reminder) | `PUT /api/reminders/{id}` | `200 OK` |
| [Withdraw a reminder](#withdraw-a-reminder) | `POST /api/reminders/{id}/withdraw` | `200 OK` |
| [Remind me again](#remind-me-again) | `POST /api/reminders/{id}/remind-again` | `200 OK` |
| [Retrieve reminders](#retrieve-reminders) | `GET /api/reminders` | `200 OK` |
| [Simulate an inbound reply](#simulate-an-inbound-reply) | `POST /api/inbound-replies` | `204 No Content` |
| [Check health](#check-health) | `GET /health` | `200 OK` |

POST and PUT requests use JSON with `Content-Type: application/json`. Example phone
numbers are fictional, and response IDs are illustrative.

## Create a reminder

`POST /api/reminders`

Save a reminder with its message, phone number, and scheduled time. Sending happens
later through the scheduler.

### Request fields

All fields are required and cannot be `null`. `message` and `phoneNumber` must be
nonblank, meaning they contain something other than whitespace. Accepted strings
are stored unchanged.

String-length limits use UTF-16 code units, matching Java's `String.length()`.
Most common letters count as one unit. Emojis like 😀 count as two units.

| Field | JSON type | Constraints and behavior |
| --- | --- | --- |
| `message` | string | Maximum 1,000 UTF-16 code units. This is an input-size limit, not an SMS segment limit. |
| `phoneNumber` | string | Maximum 255 UTF-16 code units. Phone-number format is not validated or normalized. |
| `scheduledAt` | string (date-time) | Local ISO format, such as `2030-01-15T14:30:00`, without a timezone or offset. Must be strictly after the server's current local time when validated. No caller-timezone conversion. |

### Example request

Choose a future scheduled time before running the request.

```bash
curl -i --request POST http://localhost:8080/api/reminders \
  --header 'Content-Type: application/json' \
  --data '{
    "message": "Take a walk",
    "phoneNumber": "+12035550100",
    "scheduledAt": "2030-01-15T14:30:00"
  }'
```

### Successful response

`201 Created`

```json
{
  "id": 42,
  "message": "Take a walk",
  "scheduledAt": "2030-01-15T14:30:00",
  "phoneNumber": "+12035550100",
  "status": "PENDING",
  "version": 1,
  "withdrawnAt": null
}
```

The response includes the submitted fields plus:

| Field | JSON type | Description |
| --- | --- | --- |
| `id` | integer | Server-assigned reminder identifier. |
| `status` | string | Acceptance-tracking state: `PENDING` or `SENT`. New reminders start as `PENDING`. |
| `version` | integer | Current reminder version. Copy it into `expectedVersion` when editing or withdrawing; do not assume a starting value. |
| `withdrawnAt` | string (date-time) or null | Server-local withdrawal time. A non-null value means the alert was withdrawn, even though its status remains `PENDING`. |

**Storage:** Reminders are persisted in PostgreSQL's `reminders` table. The response
contains selected fields rather than the complete database row. Delivery tracking
is stored separately in `deliveries` and is not exposed in the HTTP response.

### Errors

`400 Bad Request`

Missing or null required fields, blank strings, oversized strings, and present or
past scheduled times are rejected without saving a reminder.

For example, setting `scheduledAt` to `"2000-01-01T10:00:00"` produces a `400`.
Replace it with a future server-local time and resubmit.

Validation errors return generic JSON containing the status, error name, and
request path. They do not include field-specific validation messages or rejected
values. Use the request-field table to check the input.

Persistence failures return `503 {"code":"SAVE_FAILED"}` without database details.
The save may have committed even when its acknowledgement failed. Retrieve reminders
before deciding whether to create another; creation does not deduplicate retries.

## Edit a reminder

`PUT /api/reminders/{id}`

Correct the message or scheduled time of an alert before its first send claim.
Both `message` and `scheduledAt` are required, along with the `expectedVersion`
from the latest create/list/mutation response. Phone-number changes are not supported.
For a message-only correction, supply its existing future time.

```bash
curl -i --request PUT http://localhost:8080/api/reminders/42 \
  --header 'Content-Type: application/json' \
  --data '{
    "message": "Take a longer walk",
    "scheduledAt": "2030-01-15T15:00:00",
    "expectedVersion": 1
  }'
```

`message` uses the creation constraints. The new time must be strictly after the
server's current local time after it obtains the database locks. `expectedVersion`
must be a nonnegative integer. Accepted message text is stored unchanged.

Success returns `200 OK` with the same fields as the create response and an updated
version. The correction saves a new delivery snapshot and prevents the obsolete
intent from sending, including after restart. Earlier snapshots stay unchanged.

## Withdraw a reminder

`POST /api/reminders/{id}/withdraw`

Stop an alert before its first send claim, without deleting its saved records or
marking anything complete.

```bash
curl -i --request POST http://localhost:8080/api/reminders/42/withdraw \
  --header 'Content-Type: application/json' \
  --data '{"expectedVersion": 1}'
```

`expectedVersion` is required and must be a nonnegative integer. Success returns
`200 OK` with the reminder, an updated version, and a non-null `withdrawnAt`.
The message, scheduled time, phone number, and delivery snapshot remain stored.

### Editing and withdrawal boundaries

Both operations require an unwithdrawn `PENDING` reminder whose current delivery is
`READY` and has never been claimed. They remain available after the scheduled time
passes if no claim has happened. An edit still requires a new future time.

The send claim and corrections use the same database lock order. If the correction
commits first, the obsolete alert cannot send. If the claim commits first, the
correction conflicts; it cannot recall the attempt or prevent its outcome recording.
Two corrections using one version have only one winner.

Claimed, uncertain, rejected, accepted, sent, and withdrawn alerts cannot be edited
or withdrawn through these endpoints. Ordinary corrections cannot clear an `UNKNOWN`
hold, reset a used retry budget, or grant another attempt. Use the existing recovery
procedure for delivery uncertainty. There is no ordinary undo-withdraw operation.

### Mutation errors

Invalid required fields, malformed JSON, and invalid version values return generic
`400 Bad Request` responses without rejected values, as with creation validation.
Fractional version numbers are rejected. Malformed path IDs return a fixed response
that omits the rejected identifier. Other refusals also return a fixed JSON code:

| HTTP status | `code` | Meaning |
| --- | --- | --- |
| `400` | `INVALID_ID` | The path identifier cannot be converted to a reminder ID. |
| `400` | `INVALID_SCHEDULE` | The proposed time is no longer in the future when checked under the locks. |
| `404` | `NOT_FOUND` | The reminder does not exist. |
| `409` | `STALE_VERSION` | The reminder version changed. Retrieve it again before deciding what to do. |
| `409` | `NOT_EDITABLE` | The current alert is no longer eligible for ordinary edits or withdrawal. |
| `503` | `SAVE_FAILED` | The database operation failed or its commit could not be confirmed. |

For example: `{"code":"STALE_VERSION"}`. Validation and conflict refusals leave stored
state unchanged. A `503 SAVE_FAILED` can occur after a successful commit. Retrieve
the current reminder and version before deciding whether another correction is needed.

## Remind me again

`POST /api/reminders/{id}/remind-again`

Schedule one fresh reminder with the source's message and phone number, preserving
the source and its delivery evidence. Saving sends no confirmation text. Browsing
reminders or prefilling a form schedules nothing.

```json
{
  "operationId": "2af941dd-3fb2-4f75-a673-50b398ca468c",
  "expectedVersion": 2,
  "scheduledAt": "2030-01-15T14:30:00"
}
```

All fields are required. `operationId` is a client-generated UUID for one save;
retain it across retries. `expectedVersion` is the source's current nonnegative
integer version. Supply an explicit new server-local time. Fractional seconds are
truncated to microseconds before comparison and storage; that stored time must be
strictly future after the database locks are acquired.

| Source | Reuse allowed? |
| --- | --- |
| `SENT`, including legacy records without a delivery | Yes, with current acceptance recorded when a delivery exists. |
| Current delivery `ACCEPTED`, awaiting `SENT` finalization | Yes. Finalization cannot submit again. |
| Withdrawn, never-claimed `READY` alert | Yes. The source stays withdrawn. |
| Unwithdrawn `PENDING` with current `PERMANENT_REJECTION` | Yes. The old delivery cannot retry. |
| Active `READY` alert, including overdue alerts | No. Edit or withdraw it first. |
| `UNKNOWN` | No. Use [delivery review](delivery-recovery.md). |
| Any `RETRYABLE_REJECTION`, including exhausted retries | No. Use delivery recovery; another attempt may remain or be granted. |
| Untracked `PENDING` or inconsistent delivery state | No. |

Success returns `200 OK` with a new reminder in the usual response format. Its
fresh delivery uses the normal retry policy. The source's snapshots and budget stay
unchanged. The new reminder can be edited or withdrawn using the existing endpoints.

A successful save durably binds the source ID and operation UUID to the requested
version, canonical time, and result. Repeating that request returns the same result's
current representation, including later edits, withdrawal, or sending. Replay still
works after restart, source finalization, or the requested time passing; it creates
nothing. Reusing that source/key with a different version or canonical time returns
`409 OPERATION_CONFLICT`. A new UUID deliberately schedules a separate alert. UUIDs
are scoped to each source, and successful bindings do not expire.

Malformed or missing fields return the existing generic `400`; malformed path IDs
return `400 INVALID_ID`. New operations can also return `404 NOT_FOUND`,
`400 INVALID_SCHEDULE`, `409 STALE_VERSION`, `409 NOT_REUSABLE`, or
`409 DELIVERY_REVIEW_REQUIRED`. These use the fixed `{"code":"..."}` format.
Validation and conflict failures save nothing. Persistence failures return
`503 SAVE_FAILED` without database details. Retry the unchanged operation UUID and
payload to resolve whether a save committed when its response was lost.

## What happens next

The normal application's scheduler runs at a 60-second interval by default. It
checks due `PENDING` reminders, but the reminder status alone does not authorize a
send. A separate delivery record determines whether submission is permitted.

Creating a reminder also saves its current delivery intent: a delivery ID and a
snapshot of the phone number and full rendered message. Retries use that same ID
and snapshot. New snapshots contain `TextLoop Reminder:` followed by the message,
without reply instructions. Historical snapshots keep their original text.

For an eligible delivery, TextLoop:

1. Commits `UNKNOWN` before calling the sender, recording that the attempt's outcome
   is unresolved.
2. Calls the configured SMS sender with the saved delivery ID and snapshot.
3. Commits the sender's outcome. Acceptance is recorded as `ACCEPTED` separately
   from the reminder update.
4. Finalizes recorded acceptance by saving the reminder as `SENT`.

The current fake sender simulates acceptance and logs the delivery ID, without
logging the phone number or rendered message. It does not send a real SMS.

| Status | Meaning |
| --- | --- |
| `PENDING` | The reminder's current sender acceptance has not been finalized. Its delivery may be waiting, held, accepted but awaiting finalization, or withdrawn. |
| `SENT` | The reminder's current sender acceptance has been finalized. This does not confirm delivery to a phone. |

The scheduled time determines eligibility for processing, rather than an exact
sending time. Delivery outcomes such as `UNKNOWN` and `ACCEPTED` are internal
tracking states, not values of the API's `status` field. A non-null `withdrawnAt`
prevents sending regardless of the retained `PENDING` status.

### Retries and recovery

Automatic retries apply only to known retryable non-acceptance. Each delivery has
three total automatic attempts, including the first submission. After the first
retryable rejection, TextLoop waits at least one minute; after the second, it waits
at least five minutes. The attempt count and retry timing survive restart. Actual
retry processing happens on a subsequent scheduler pass.

Unresolved attempts, permanent rejections, and exhausted retries are held for
recovery. They are not automatically resubmitted. A `PENDING` status therefore does
not prove that no submission occurred or that another attempt is permitted.

If acceptance was committed but saving `SENT` failed, a later scheduler pass
finalizes the recorded acceptance without another submission. If the sender's
outcome was not committed, the delivery remains `UNKNOWN` for recovery.

For inspection and recovery commands, see [Local delivery recovery](delivery-recovery.md).
Recovery supports the local single-instance workflow and must run with the normal
application stopped; it disables HTTP and scheduled processing.

### Current limitations

Known rejections do not block other eligible reminders. Unexpected sender or
database exceptions can still stop the current processing pass.

Real SMS is not implemented. The recovery safeguards do not guarantee exactly-once
external SMS submission; provider-specific behavior and protections still need
verification before connecting a real sender.

## Retrieve reminders

`GET /api/reminders`

Retrieve all stored reminders, including withdrawn reminders, and their current statuses.

### Example request

```bash
curl -i http://localhost:8080/api/reminders
```

### Successful response

`200 OK`

```json
[
  {
    "id": 42,
    "message": "Take a walk",
    "scheduledAt": "2030-01-15T14:30:00",
    "phoneNumber": "+12035550100",
    "status": "PENDING",
    "version": 1,
    "withdrawnAt": null
  }
]
```

Each array item has the same fields as the create response. When no reminders
exist, the response is `[]`.

The endpoint returns reminders across all phone numbers, without owner filtering,
pagination, or guaranteed ordering.

To observe background processing, retrieve the list again after a reminder becomes
due. Its status changes to `SENT` after sender acceptance is recorded and finalized.
For reminders that remain `PENDING`, see [Retries and recovery](#retries-and-recovery).

### Upcoming, Recent, and detail

- `GET /api/reminders/upcoming`: unwithdrawn `PENDING` reminders scheduled strictly
  after the current server-local time, ordered by scheduled time then ID, ascending.
- `GET /api/reminders/recent`: reminders scheduled within the last seven days,
  ordered by scheduled time then ID, descending. The lower boundary is exclusive;
  the current time is inclusive. Withdrawn and held/failed reminders are included.
- `GET /api/reminders/{id}`: one stored reminder, including outside either window.
  Missing records return `404 NOT_FOUND`; malformed IDs return `400 INVALID_ID`,
  using the fixed JSON code format above.

Recent uses `scheduledAt`, so a reminder created months ago and due yesterday is
included. Retries do not create separate rows or move the window. Comparisons use
stored microsecond precision and one clock reading per collection request.

These responses contain the usual reminder fields plus `canEdit`, `canWithdraw`,
`canRemindAgain`, `feedback`, and nullable `nextAttemptAt`. Collection responses use
arrays, including `[]` when empty. Feedback describes the current alert:

| `feedback` | Meaning |
| --- | --- |
| `WAITING` | Submission has not begun. A past scheduled time does not mean it sent. |
| `ACCEPTED` | Sender acceptance is recorded, possibly awaiting `SENT` finalization. This does not confirm handset delivery. |
| `WITHDRAWN` | The never-claimed alert was withdrawn. |
| `RETRY_WAITING` | Another attempt is permitted. `nextAttemptAt` is its earliest eligible time, not a promised send time. |
| `RETRY_EXHAUSTED` | The retry budget is used. Review delivery recovery before another attempt. |
| `PERMANENT_REJECTION` | The sender rejected the submission. Remind me again can schedule a fresh alert. |
| `DELIVERY_UNCERTAIN` | Submission outcome is unresolved. Use delivery review before scheduling again. |
| `LEGACY_SENT` | A legacy record is marked `SENT` without a delivery record. Acceptance evidence is unavailable. |
| `UNTRACKED` / `INCONSISTENT_DELIVERY` | Delivery information is missing or inconsistent. Ordinary actions are unavailable. |

Action flags describe the read snapshot; saves recheck eligibility under locks and
may conflict. Use the returned `version` for edits, withdrawal, or a new reuse
operation. A previously successful reuse operation can still be replayed even when
`canRemindAgain` is now false. `nextAttemptAt` is null when no retry is permitted.

Browsing, detail, and refresh create no deliveries, invoke no sending or legacy
adoption, and change no statuses. Leaving a window preserves records and delivery
evidence. Older records remain discoverable through the unfiltered list and detail.
These reads have the same local, unauthenticated scope as the existing list.

## Simulate an inbound reply

`POST /api/inbound-replies`

Submit a simulated incoming message to exercise the receipt endpoint. It
acknowledges input without interpreting commands, saving replies, or changing
reminders.

### Request fields

All fields are required and cannot be `null`. Both strings must be nonblank.
Accepted values pass through unchanged, without trimming or case conversion.

| Field | JSON type | Constraints and behavior |
| --- | --- | --- |
| `fromPhoneNumber` | string | No phone-format validation or explicit field-length limit. |
| `body` | string | No command validation or explicit field-length limit. |

### Example request

```bash
curl -i --request POST http://localhost:8080/api/inbound-replies \
  --header 'Content-Type: application/json' \
  --data '{
    "fromPhoneNumber": "+12035550100",
    "body": "DONE"
  }'
```

### Successful response

`204 No Content`, with an empty response body.

Submitting `DONE` does not complete a reminder. Other nonblank messages are also
accepted because the receipt endpoint does not use command parsing yet.

### Errors

`400 Bad Request`, with an empty response body.

Missing, null, or blank fields are rejected. Empty bodies, malformed JSON, and
unreadable request structures are also rejected.

For example, setting `body` to `"   "` produces an empty `400` response. Replace it
with a nonblank string and resubmit.

Unlike reminder validation errors, inbound reply errors have no JSON body to parse.

## Check health

`GET /health`

Check whether the application responds to an HTTP request.

### Example request

```bash
curl -i http://localhost:8080/health
```

### Successful response

`200 OK`, with a plain-text response body:

```text
TextLoop is running
```

This endpoint does not check database connectivity, scheduler progress, or sending
health.
