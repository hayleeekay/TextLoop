# Local delivery recovery

Stop the normal application before running recovery. Use the same private database
settings as your local application. Recovery is for the single-instance, fake-SMS
workflow; it disables HTTP and scheduled processing and never submits an SMS itself.
The stopped-application acknowledgement is an operator check, not a process lock.

Build and list delivery records:

```sh
./mvnw -DskipTests package
java -jar target/textloop-0.0.1-SNAPSHOT.jar --textloop.recovery=true
```

The first startup initializes missing delivery records for legacy pending reminders.
Overdue rows become `UNKNOWN` because their previous send outcome was not recorded.
Future rows become `READY`, assuming they have never been due and their schedule has
not been changed. Existing `SENT` reminders are preserved without inventing receipts.
Later lists do not change existing delivery outcomes.

The list omits phone numbers, message text, and provider references. It includes the
current-intent flag, outcome/reason, attempt count, next retry time, exhaustion,
recovery metadata, and the version token required for writes. Older unknown records
remain listed after replacement. An `ACCEPTED` current delivery with a `PENDING`
reminder needs finalization.

| State | Meaning |
| --- | --- |
| `READY` | No submission has begun for this intent. |
| `RETRYABLE_REJECTION` | Known non-acceptance, eligible within the retry budget. |
| `PERMANENT_REJECTION` | Known non-acceptance; unchanged submissions are held. |
| `UNKNOWN` | Unresolved during a call and after interruption; automatic sending is held. |
| `ACCEPTED` | Sender acceptance committed; reminder finalization may remain. |

`PENDING` alone does not authorize a send. `SENT` means the reminder's current sender
acceptance was finalized, not that a handset received it. Fake acceptance is simulated.
Automatic delivery permits three total attempts, waiting at least one minute and
then five minutes after the respective retryable rejections. Count and timing survive
restart. Permanent rejection, uncertainty, and exhausted retries remain discoverable.

To authorize one more attempt for an exhausted retryable rejection, copy the current
UUID and version from the list into this command:

```sh
java -jar target/textloop-0.0.1-SNAPSHOT.jar --textloop.recovery=true \
  --recovery.action=retry-once --recovery.delivery=DELIVERY_UUID \
  --recovery.version=VERSION --recovery.app-stopped=true
```

Recovery commits the permission; normal processing consumes it once. Another rejection
returns to the hold. Restart the normal application when recovery is complete. The
same old command token cannot grant a new permission after the first was consumed.
List again before each new recovery decision. Refused commands exit with code 2.

Other actions use the same delivery, version, and stopped-application options:

| Action | Required state and decision |
| --- | --- |
| `finalize` | `ACCEPTED`: finish the reminder update without submitting again. |
| `confirm-accepted` | `UNKNOWN`: positive evidence of sender acceptance. |
| `confirm-retryable-rejection` | `UNKNOWN`: positive evidence of temporary non-acceptance; preserve the remaining budget. |
| `confirm-permanent-rejection` | `UNKNOWN`: positive evidence of permanent non-acceptance. |
| `replace-unknown` | `UNKNOWN`: intentionally submit again despite duplicate risk; also pass `--recovery.ack=duplicate-risk`. |

Missing logs, elapsed time, or an absent callback are not evidence of non-acceptance.
A replacement creates a linked new delivery ID with the original payload snapshot;
the original uncertainty is preserved. All writes require the expected version and
current pending intent. Stale or inactive intents are refused. Do not reset reminder
status or edit delivery rows by hand to bypass these checks.

Retries preserve the intended delivery ID. A future snooze must create a new intent;
snooze/cancel behavior is not implemented here. Separate commits protect recorded
acceptance from a later reminder-update failure. They do not guarantee exactly-once
external SMS. Real-provider mapping, reconciliation, and retry protections still need
provider-specific verification before real SMS is enabled.
