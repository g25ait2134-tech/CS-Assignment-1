# RSA Demo LLD

## Dashboard

`demo.DashboardApp` serves:

- `/`: browser dashboard.
- `/events`: Server-Sent Events stream from the shared JSONL log file.

Configuration:

- `LOG_FILE`: defaults to `/logs/events.jsonl`.
- `DASHBOARD_PORT`: defaults to `8090`.
- `DASHBOARD_INITIAL_EVENTS`: defaults to `5000`.

The browser stores up to 5,000 events and renders the log table inside a scrollable pane. The `Follow Live` button controls whether the pane auto-scrolls to the newest log or lets the presenter inspect older events during load bursts.
