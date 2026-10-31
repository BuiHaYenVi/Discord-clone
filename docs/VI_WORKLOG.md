# VI — Application Protocol & Reliability Work Log

## Base and scope

This work is prepared from the source snapshot in `feature/2380602574-update-client-handler-manager.zip` so it includes Vinh's latest supplied `ClientHandler`/`ClientConnection` work. The ZIP files do not contain Git history, so this package is a local feature snapshot, not a pushed branch or a merged pull request. Hải's separate Data Layer changes are not claimed as merged.

## Changes implemented

| Task | Status | Evidence |
|---|---|---|
| VI-01 — Application protocol contract | Done | `docs/PROTOCOL.md`; line-based pipe framing and Base64 UTF-8 fields documented |
| VI-02 — Request/response correlation | Done | Client appends UUID `@RID`; server emits `ACK|id|OK` after successful handling |
| VI-03 — Protocol handler validation | Done | Client and Server `ProtocolHandler` reject empty, multi-line and oversized frames; invalid Base64 reports a clear exception |
| VI-04 — Message/request IDs | Done | Unique UUID per request; existing persisted chat message ID remains separate |
| VI-05 — Application ACK | Done | Server correlates ACK to the request ID; errors do not receive a success ACK |
| VI-06 — Error protocol | Done | Shared `ProtocolErrorCode` enums and stable machine-readable codes |
| VI-07 — Heartbeat | Done | Client sends `PING` every 15s; server replies `PONG` |
| VI-08 — Timeout | Done | 5s connect timeout, 10s request deadline, 40s heartbeat timeout, 45s server read timeout |
| VI-09 — Documentation/tests | Done | Protocol documentation, client/server protocol tests, fake-peer ACK test |

## Verification performed

Using the available Java 21 compiler with `--release 21`:

- Client source compilation: PASS
- Client protocol regression test: PASS
- Client fake-peer request-ID/ACK test: PASS
- Server source compilation: PASS
- Server protocol regression test: PASS

The NetBeans project properties target JDK 23, but this environment only has JDK 21. The source and tests compiled against Java 21; a final build in the team's JDK 23 / NetBeans environment is still required. Live PostgreSQL and multi-client integration testing has not been performed here.

## Important behavior decisions

1. A request timeout does not automatically retry the command. The server may have completed the request while its response was delayed; retrying `SEND_MESSAGE` could duplicate it.
2. An application ACK confirms handler completion, not that every recipient displayed/read the message.
3. Reconnect is intentionally not implemented here because it belongs to An's Client/Connection task. This module detects a broken connection and exposes a disconnected event for the client layer to react to.
4. Client and Server protocol changes must be merged together. A new client paired with an old server will not receive ACKs and may report request timeouts.

## Suggested Git workflow for Vi

Branch name:

```text
feature/2380602519-application-protocol-reliability
```

Suggested commits (split them if you apply the changes manually):

```text
feat(protocol): validate frames and add request IDs
feat(protocol): add application ACK and stable error codes
feat(network): add heartbeat and request timeouts
test(protocol): add framing and ACK regression tests
docs(protocol): document client-server wire contract
```

Suggested PR title:

```text
feat: add application protocol reliability
```

Before opening the PR, rebase/merge the team's agreed base branch, run the project on JDK 23, and test against the merged Server/Data Layer with two real clients.
