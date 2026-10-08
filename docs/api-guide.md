# TextLoop API

TextLoop's development API supports creating and retrieving reminders, simulating
inbound replies, and checking whether the application responds.

These examples assume the backend is running locally at `http://localhost:8080`.
Adjust the URL if you use another port. There is currently no authentication or
hosted API.

| Action | Endpoint | Successful response |
| --- | --- | --- |
| [Create a reminder](#create-a-reminder) | `POST /api/reminders` | `201 Created` |
| [Retrieve reminders](#retrieve-reminders) | `GET /api/reminders` | `200 OK` |
| [Simulate an inbound reply](#simulate-an-inbound-reply) | `POST /api/inbound-replies` | `204 No Content` |
| [Check health](#check-health) | `GET /health` | `200 OK` |

POST requests use JSON with `Content-Type: application/json`. Example phone numbers
are fictional, and response IDs are illustrative.

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
  "status": "PENDING"
}
```

The response includes the submitted fields plus:

| Field | JSON type | Description |
| --- | --- | --- |
| `id` | integer | Server-assigned reminder identifier. |
| `status` | string | Current reminder state: `PENDING` or `SENT`. New reminders start as `PENDING`. |

**Storage:** Reminders are persisted in PostgreSQL's `reminders` table. The response
contains selected fields rather than the complete database row.

### Errors

`400 Bad Request`

Missing or null required fields, blank strings, oversized strings, and present or
past scheduled times are rejected without saving a reminder.

For example, setting `scheduledAt` to `"2000-01-01T10:00:00"` produces a `400`.
Replace it with a future server-local time and resubmit.

Validation errors return generic JSON containing the status, error name, and
request path. They do not include field-specific validation messages or rejected
values. Use the request-field table to check the input.

## What happens next

The scheduler is configured to run at a 60-second interval. Each pass selects
`PENDING` reminders whose scheduled time has arrived or passed.

For each selected reminder, TextLoop:

1. Builds the reminder message.
2. Calls the configured SMS sender.
3. After the sender returns successfully, saves the reminder as `SENT`.

The current fake sender logs the phone number and rendered message. It does not
send a real SMS, so use fictional data when trying the flow.

| Status | Meaning |
| --- | --- |
| `PENDING` | The reminder has not been recorded as successfully sent. It becomes eligible for processing when due. |
| `SENT` | The sender returned successfully and the resulting status was saved. This does not confirm delivery to a phone. |

The scheduled time determines eligibility for processing, rather than an exact
sending time. Later passes skip reminders already persisted as `SENT`.

### Current limitations

- A sending or saving exception stops the remaining reminders in that pass.
- If the sender throws, the service does not save `SENT`. The reminder remains
  eligible for a later pass.
- If sending succeeds but saving `SENT` fails, a later pass may send the reminder
  again.

A `PENDING` status therefore does not prove that no sending attempt occurred.

## Retrieve reminders

`GET /api/reminders`

Retrieve all stored reminders and their current statuses.

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
    "status": "PENDING"
  }
]
```

Each array item has the same fields as the create response. When no reminders
exist, the response is `[]`.

The endpoint returns reminders across all phone numbers, without owner filtering,
pagination, or guaranteed ordering.

To observe background processing, retrieve the list again after a reminder becomes
due. Its status changes to `SENT` after successful sending and persistence.

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
accepted because command parsing is not implemented.

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
