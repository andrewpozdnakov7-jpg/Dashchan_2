# Auto-bump

Auto-bump is an opt-in Dvach-only background feature for users with a saved
Dvach Passcode. It posts numbered text messages to selected existing threads.
The global switch is disabled by default.

## Entry points

- Settings -> Forum -> Dvach -> Auto-bump controls the global switch and opens
  the task manager.
- The current thread overflow menu contains "Add to auto-bump".
- The manager can create a task from a pasted thread address or a Dvach
  favorite.

Each task stores the board and thread, message template, next counter value,
interval, next planned run, last known bump activity, consecutive failure count
and enabled state.
`{n}` in a template is replaced with the counter. If the marker is absent, the
counter is appended to the message. The counter is limited to 1 through 2000;
after message 2000 is confirmed, the task is disabled.

## Scheduling

`AutoBumpWorker` uses a unique one-time WorkManager request. After a run it
schedules the next enabled task, so each task can have its own interval.
WorkManager execution is intentionally inexact. The user interval is limited to
15 through 525600 minutes (one year).

The worker postpones an auto-bump while a regular post is being sent. A Dvach
"too fast" response postpones the task without consuming an error attempt.
Only one due task is attempted per worker run, and attempts for different tasks
are spaced by at least one minute.

Before posting, the worker reads the thread. A newer non-sage post restarts the
task interval without sending an auto-bump. A successful manual non-sage post
from the application also restarts the interval immediately. Sage posts do not
affect the timer. Closed, deleted and archived threads and threads at their bump
limit are paused instead of receiving another message.

The current thread menu shows the effective task state. For an active task it
shows the approximate number of minutes until the next background check.
WorkManager may run that check later than displayed.

## Failure handling

The counter advances only after a confirmed successful response. If delivery
may have succeeded but the response cannot be confirmed, the task is paused
immediately instead of automatically repeating the same numbered message.

A task is paused immediately when its thread is missing or closed, access is
denied, or the saved Passcode cannot authorize posting. Other errors are
recorded and the task is paused after five consecutive failures. A notification
opens the affected thread and includes the failure reason.

## Storage

`AutoBumpStorage` writes task state to the application's private JSON storage.
At most 10 tasks are retained. Scheduling is restored when the main application
process starts.
