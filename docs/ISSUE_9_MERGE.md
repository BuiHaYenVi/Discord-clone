# Issue 9 integration

The integration merges `feature/2380600023-issue-9-chat-ui` at `3b34ce5`
into the existing `feature/discord-web-clone` architecture. The integration
branch is `codex/merge-issue-9`.

## Milestones

1. Merge and backend/client compatibility (`cd68e55`): preserve the React
   workspace, rich message DAO, protocol v2, JWT and EventBus; integrate the
   legacy TCP commands and the updated Java client. All 15 conflicted files
   are resolved.
2. Channel management UI (`c41977e`): rename, topic editing, owner-confirmed
   deletion and channel-scoped role controls in the existing workspace.
3. Regression verification: standalone compatibility and HTTP tests, a
   repeatable test runner and these verification notes.

## Compatibility decisions

- REST stays on port `8080`, WebSocket on `8081`, and the optional legacy
  HTTP/SSE bridge uses `8082`. `VITE_BRIDGE_BASE_URL` can override the legacy
  bridge URL. The active frontend continues to use `apiClient`/`wsClient`.
- `ClientApplication --swing` selects the Java Swing client. The incoming
  default browser mode is retained; the active web UI requires the current
  REST/WebSocket backend.
- The existing `edited_at`/`deleted_at` message fields remain authoritative.
  Legacy DAO names adapt to the rich DAO instead of creating a second edit
  implementation or using an undeclared `is_edited` SQL column.
- Room descriptions map to the existing `topic` field. No migration adding
  `description`, `invite_code` or `is_edited` is required by this merge.
- TCP receives the canonical `MSG_EDITED`/`MSG_DELETED` frames and compatible
  `MESSAGE_EDITED`/`MESSAGE_DELETED` frames. WebSocket receives one logical
  message event for each operation.
- `HISTORY_PAGE` verifies membership and limits pages to 1–200 messages.
- Room mutations derive permissions from server-side membership. Ownership
  transfer checks both members and changes roles/creator in a transaction.
  Room ownership is independent of guild ownership.
- Credentialed bridge requests only allow HTTP origins on `localhost` or
  `127.0.0.1`, on port `5173` or the bridge's own bound port. Mutations also
  require an allowed Origin/Referer. Auth frames are redacted in telemetry.

## Channel API

| Endpoint | Request | Required role | Response |
| --- | --- | --- | --- |
| `PATCH /api/channels/{id}` | `{ "name": "general", "topic": "Discussion" }` | Room OWNER or ADMIN | Updated channel DTO |
| `DELETE /api/channels/{id}` | No body | Room OWNER | `{ "deleted": true, "channelId": 1 }` |
| `PATCH /api/channels/{id}/members/{userId}/role` | `{ "role": "ADMIN" }` | Room OWNER | Updated member |

Names must contain 1–100 characters after trimming; topics are limited to
1024 characters. Assigning `OWNER` through the member-role endpoint performs
an ownership transfer to an existing member. It does not create a second
owner or change guild privileges. Channel updates and deletions emit
`CHANNEL_UPDATED`/`CHANNEL_DELETED`; role changes emit `MEMBER_ROLE_UPDATED`.

## Run verification

Prerequisites: JDK 21+, Node/npm, frontend dependencies installed from the
lockfile, and the existing JAR dependencies in `Chatserver/lib`. The three
Swing UI tests require an interactive Windows desktop; the runner launches
their Java processes with hidden console windows. Other tests use headless
mode.

```powershell
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-merge.ps1
```

The script compiles both Java modules with `--release 21`, runs each test
program with a timeout, and runs the frontend build and lint. It writes logs
under the ignored `Chatserver/build/merge-tests/` directory. Use
`-SkipFrontend` to run only the Java suites.

`ChatClient/build.xml` also has a standalone `test` target listing the 12
client test programs. Ant was unavailable during this integration; the XML
was parsed and the same test mains were run directly with Java.

## Verified on 18 October 2026

- All server and client Java sources and tests compile for Java 21.
- All 15 included Java test programs pass: 12 client programs and 3 server
  programs. Server totals include 42 compatibility assertions, 12 HTTP
  channel-management assertions and 84 gateway/protocol assertions.
- Frontend `npm run build` passes. `npm run lint` exits successfully with
  four warnings: two synchronous state updates in the legacy `LoginView`
  effects and two Fast Refresh export warnings in `appStore`.
- Browser testing in demo mode confirms editing a channel name/topic,
  displaying the delete confirmation, deleting the active channel and
  selecting a remaining channel. The current workspace design is retained.

The new server tests use fake DAOs/services and loopback sockets/HTTP. They
do not connect to PostgreSQL. `EndToEndWebAuthTest` requires a separately
configured live TCP server and database and is excluded from the default
runner. No live database migration or Web/Java end-to-end database session
was run as part of this integration.
