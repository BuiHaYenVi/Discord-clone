# Discord Web Clone — REST, WebSocket, TCP & UDP API Contract

> **Single Source of Truth** for Backend (`server/` Java 21 Multi-Protocol Server) and Frontend (`web/` React 19 + TypeScript + Vite SPA).

---

## 1. Architecture & Multi-Protocol Topology

### 1.1 Unified Data Model (`channel_id == room_id`)

- The PostgreSQL `rooms` table is extended with Discord Guild metadata (`guild_id`, `channel_type` `"text" | "voice"`, `position`, `topic`).
- **Every Guild Channel's `id` (`channelId`) is literally `rooms.id` (`roomId`).**
- Messages sent from the React Web UI (`SEND_MESSAGE` over WebSocket `:8081`) and messages sent from the legacy Java Swing desktop client (`ROOM_MSG` over TCP `:5000`) are persisted to the same `messages` table and broadcast simultaneously to **both** WebSocket sessions and TCP socket clients in real time.
- Default Guild (`id = 1`, name `"Discord Mini Community"`) automatically owns all pre-existing rooms (`General`, `Gaming`, `Study`, etc.).

### 1.2 Ports & Deployment Modes

| Mode | Trigger | HTTP REST | WebSocket | TCP Socket | UDP Datagram |
|------|---------|-----------|-----------|------------|--------------|
| **Local Multi-Port (Default)** | `PORT` env var unset | `:8080` (`/api/*`) | `:8081` (`ws://localhost:8081?token=<jwt>`) | `:5000` | `:5001` |
| **Cloud Single-Port (Render)** | `PORT` env var set | `:$PORT` (`/api/*`) | `:$PORT/ws` (or `:8081` locally) | `:5000` (internal) | `:5001` (internal + `UDP_FALLBACK` over WS) |

- **CORS Policy**: All `/api/*` endpoints include `Access-Control-Allow-Origin: *` (or configured origin), `Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS`, and `Access-Control-Allow-Headers: Authorization, Content-Type, X-File-Name`.
- **Authentication**:
  - **REST**: `Authorization: Bearer <jwt>` header (stateless HMAC-SHA256 JWT, 7-day expiry, signed with `JWT_SECRET` env var or deterministic dev secret).
  - **WebSocket**: Query parameter `?token=<jwt>` on handshake (`ws://localhost:8081?token=<jwt>`).

---

## 2. Shared TypeScript Interfaces (`web/src/types/api.ts`)

Copy-pasteable TypeScript definitions shared across Frontend (`T1.D`, `T1.E`, `T1.F`) and mirrored by Backend JSON serializers (`T1.C`):

```typescript
export type PresenceStatus = 'ONLINE' | 'IDLE' | 'DND' | 'OFFLINE';
export type MemberRole = 'OWNER' | 'ADMIN' | 'MEMBER';
export type ChannelType = 'text' | 'voice';
export type TransportType = 'TCP' | 'UDP' | 'WS' | 'HTTP';

export interface User {
  id: number;
  username: string;
  displayName: string;
  avatarColor?: string;
  status: PresenceStatus;
  customStatus?: string | null;
  createdAt?: string; // ISO-8601
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface Guild {
  id: number;
  name: string;
  iconText: string;
  ownerId: number;
  createdAt: string;
  channels: Channel[];
  unreadCount?: number;
}

/**
 * Note: Channel.id === rooms.id (roomId).
 * Web channels and Swing TCP rooms share the exact same primary key.
 */
export interface Channel {
  id: number;          // Identical to legacy room_id
  guildId: number;
  name: string;
  type: ChannelType;   // 'text' | 'voice'
  topic: string;
  position: number;
  unreadCount?: number;
  lastReadMessageId?: number | null;
  lastMessageAt?: string | null;
}

export interface ChannelMember {
  userId: number;
  username: string;
  displayName: string;
  role: MemberRole;    // 'OWNER' | 'ADMIN' | 'MEMBER'
  status: PresenceStatus;
  customStatus?: string | null;
  transport?: TransportType; // Shows whether user is connected via WS or legacy TCP
}

export interface Attachment {
  attachmentId: number;
  messageId?: number | null;
  filename: string;
  contentType: string;
  sizeBytes: number;
  url: string;         // e.g. "/api/files/42"
  createdAt?: string;
}

export interface ReactionGroup {
  emoji: string;
  count: number;
  userIds: number[];
  me: boolean;
}

export interface ReplyPreview {
  id: number;
  senderId: number;
  senderUsername: string;
  senderDisplayName: string;
  content: string;
}

export interface Message {
  id: number;
  channelId?: number | null;       // Present for guild channel messages (=== roomId)
  conversationId?: number | null;  // Present for Direct Messages (DM)
  senderId: number;
  senderUsername: string;
  senderDisplayName: string;
  content: string;
  replyTo?: ReplyPreview | null;
  attachments: Attachment[];
  reactions: ReactionGroup[];
  pinned: boolean;
  createdAt: string;               // ISO-8601 UTC
  editedAt?: string | null;        // ISO-8601 UTC when edited
  deletedAt?: string | null;       // ISO-8601 UTC when soft-deleted
  transport?: TransportType;       // 'WS' | 'TCP' (origin transport for Network Monitor badge)
}

export interface DirectConversation {
  id: number;
  peerUser: User;
  lastMessage?: Message | null;
  unreadCount: number;
  updatedAt: string;
}

export type FriendRequestStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export interface FriendRequest {
  id: number;
  fromUser: User;
  toUser: User;
  status: FriendRequestStatus;
  createdAt: string;
}

export interface FriendsOverview {
  friends: User[];
  pendingIncoming: FriendRequest[];
  pendingOutgoing: FriendRequest[];
}

export interface InviteInfo {
  code: string;
  guildId: number;
  guildName?: string;
  creatorId: number;
  expiresAt: string | null;
  maxUses: number;
  uses: number;
}

export interface VoiceParticipant {
  userId: number;
  username: string;
  displayName: string;
  channelId: number;
  muted: boolean;
  cameraOn: boolean;
}

export interface WsEnvelope<T = unknown> {
  type: string;
  rid?: string; // Optional request correlation UUID from client
  payload: T;
}

export interface NetworkFrameLog {
  id?: string;
  direction: 'IN' | 'OUT';
  transport: TransportType; // 'TCP' | 'UDP' | 'WS' | 'HTTP'
  rawFrame: string;
  timestampMs: number;
}

export interface RttSample {
  seq: number;
  clientTimestampMs: number;
  serverTimestampMs: number;
  wsRttMs: number;
  udpRttMs?: number | null;
  transport: TransportType;
}

export interface HealthStatus {
  status: 'UP' | 'DEGRADED';
  dbConnected: boolean;
  ports: {
    tcp: number;
    udp: number;
    http: number;
    ws: number;
  };
  onlineSessions: {
    total: number;
    ws: number;
    tcp: number;
  };
  udpActive: boolean;
}
```

---

## 3. REST API Contract (`/api/*`)

### 3.1 Standard Error Format

All non-2xx HTTP responses return a consistent JSON object:

```json
{
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Invalid or expired JWT token"
  }
}
```

| HTTP Status | Error Code | Description |
|-------------|------------|-------------|
| `400 Bad Request` | `VALIDATION_ERROR` | Missing required fields or invalid parameter format |
| `401 Unauthorized` | `UNAUTHORIZED` | Missing/invalid Bearer token or bad login credentials |
| `403 Forbidden` | `FORBIDDEN` | Insufficient role permission (`OWNER`/`ADMIN` required) or not a member |
| `404 Not Found` | `NOT_FOUND` | Guild, channel, message, invite, or attachment does not exist |
| `409 Conflict` | `CONFLICT` | Username already exists or duplicate friend request |
| `413 Payload Too Large` | `FILE_TOO_LARGE` | Uploaded file exceeds 5 MB (5,242,880 bytes) |
| `415 Unsupported Media Type` | `UNSUPPORTED_MEDIA_TYPE` | Disallowed MIME type on `PUT /api/files` |
| `500 Internal Server Error` | `INTERNAL_ERROR` | Unexpected server/database error |

---

### 3.2 Authentication Endpoints

#### `POST /api/auth/register`
Creates a new user account, automatically adds the user to the default Guild (`id = 1`) and default `General` channel (`id = 1`), and returns a signed JWT.

- **Auth required**: No
- **Request Body**:
  ```json
  {
    "username": "alice",
    "password": "secretPassword123",
    "displayName": "Alice Nguyen"
  }
  ```
- **Response `201 Created`**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 12,
      "username": "alice",
      "displayName": "Alice Nguyen",
      "status": "ONLINE"
    }
  }
  ```

#### `POST /api/auth/login`
Authenticates an existing user (compatible with existing SHA-256 / password hashes in `users` table) and returns a signed JWT.

- **Auth required**: No
- **Request Body**:
  ```json
  {
    "username": "alice",
    "password": "secretPassword123"
  }
  ```
- **Response `200 OK`**: Same shape as `AuthResponse` (`{ token, user }`).

#### `GET /api/auth/me`
Returns the current authenticated user profile from the `Authorization: Bearer <jwt>` token.

- **Auth required**: Yes
- **Response `200 OK`**:
  ```json
  {
    "user": {
      "id": 12,
      "username": "alice",
      "displayName": "Alice Nguyen",
      "status": "ONLINE",
      "customStatus": "Studying Computer Networks"
    }
  }
  ```

---

### 3.3 Guilds, Invites & Channels (`channel_id == room_id`)

#### `GET /api/guilds`
Lists all guilds the authenticated user belongs to, including their channels (`rooms` rows) and unread counts computed from `channel_reads`.

- **Auth required**: Yes
- **Response `200 OK`**:
  ```json
  {
    "guilds": [
      {
        "id": 1,
        "name": "Discord Mini Community",
        "iconText": "DM",
        "ownerId": 1,
        "createdAt": "2026-10-17T03:00:00Z",
        "channels": [
          {
            "id": 1,
            "guildId": 1,
            "name": "general",
            "type": "text",
            "topic": "General discussion bridged with Swing TCP clients",
            "position": 0,
            "unreadCount": 3,
            "lastReadMessageId": 140
          },
          {
            "id": 2,
            "guildId": 1,
            "name": "General Voice",
            "type": "voice",
            "topic": "WebRTC P2P Voice/Video Lounge",
            "position": 1,
            "unreadCount": 0,
            "lastReadMessageId": null
          }
        ]
      }
    ]
  }
  ```

#### `POST /api/guilds`
Creates a new guild, adds the creator as `OWNER`, and automatically provisions a default `#general` text channel and `General Voice` voice channel.

- **Auth required**: Yes
- **Request Body**:
  ```json
  {
    "name": "Network Lab Group 4",
    "iconText": "NL"
  }
  ```
- **Response `201 Created`**:
  ```json
  {
    "guild": {
      "id": 2,
      "name": "Network Lab Group 4",
      "iconText": "NL",
      "ownerId": 12,
      "createdAt": "2026-10-17T04:00:00Z",
      "channels": [
        { "id": 8, "guildId": 2, "name": "general", "type": "text", "topic": "General Text Channel", "position": 0, "unreadCount": 0 },
        { "id": 9, "guildId": 2, "name": "General Voice", "type": "voice", "topic": "Voice Channel", "position": 1, "unreadCount": 0 }
      ]
    }
  }
  ```

#### `POST /api/guilds/{guildId}/invites`
Generates a shareable invite code (`6–8` alphanumeric characters) for the guild.

- **Auth required**: Yes (Guild member)
- **Request Body**:
  ```json
  {
    "ttlMinutes": 1440,
    "maxUses": 25
  }
  ```
- **Response `201 Created`**:
  ```json
  {
    "code": "LTM2026",
    "guildId": 2,
    "creatorId": 12,
    "expiresAt": "2026-10-18T04:00:00Z",
    "maxUses": 25,
    "uses": 0
  }
  ```

#### `POST /api/invites/{code}/join`
Joins the guild associated with `code` and enrolls the user in all of the guild's channels (`room_members`) with role `MEMBER`. Broadcasts `MEMBER_JOINED` to online guild members.

- **Auth required**: Yes
- **Response `200 OK`**:
  ```json
  {
    "guild": {
      "id": 2,
      "name": "Network Lab Group 4",
      "iconText": "NL",
      "ownerId": 12,
      "createdAt": "2026-10-17T04:00:00Z",
      "channels": [ ... ]
    }
  }
  ```

#### `GET /api/guilds/{guildId}/channels`
Lists all channels (`text` and `voice`) in the specified guild ordered by `position ASC, id ASC`.

- **Auth required**: Yes
- **Response `200 OK`**: `{ "channels": Channel[] }`

#### `POST /api/guilds/{guildId}/channels`
Creates a new channel (`rooms` row with `guild_id = guildId`) and enrolls guild members. Broadcasts `CHANNEL_CREATED`.

- **Auth required**: Yes (`OWNER` or `ADMIN` in guild)
- **Request Body**:
  ```json
  {
    "name": "packet-captures",
    "type": "text",
    "topic": "Wireshark & TCP/UDP frame analysis",
    "position": 2
  }
  ```
- **Response `201 Created`**: `{ "channel": Channel }`

#### `GET /api/channels/{channelId}/members`
Lists all members of a channel (`room_members`) with their role (`OWNER` | `ADMIN` | `MEMBER`), live presence (`ONLINE` | `IDLE` | `DND` | `OFFLINE`), and active transport (`WS` | `TCP`).

- **Auth required**: Yes
- **Response `200 OK`**:
  ```json
  {
    "members": [
      {
        "userId": 1,
        "username": "admin",
        "displayName": "System Admin",
        "role": "OWNER",
        "status": "ONLINE",
        "customStatus": null,
        "transport": "TCP"
      },
      {
        "userId": 12,
        "username": "alice",
        "displayName": "Alice Nguyen",
        "role": "MEMBER",
        "status": "ONLINE",
        "customStatus": "Studying Computer Networks",
        "transport": "WS"
      }
    ]
  }
  ```

#### `PATCH /api/channels/{channelId}/members/{userId}/role`
Updates a member's role in the channel/guild (`ADMIN` or `MEMBER`). Requires `OWNER` or `ADMIN` role.

- **Auth required**: Yes (`OWNER` or `ADMIN`)
- **Request Body**:
  ```json
  {
    "role": "ADMIN"
  }
  ```
- **Response `200 OK`**: `{ "member": ChannelMember }`

---

### 3.4 Messages, Pins, Full-Text Search & Read State

#### `GET /api/channels/{channelId}/messages?beforeId=<id>&limit=50`
Cursor-paginated message history for a guild channel (`channelId == roomId`). Returns messages ordered chronologically (`ASC` within the returned page, or latest `limit` messages prior to `beforeId`). Includes `replyTo`, `attachments`, `reactions`, `pinned`, `editedAt`, and `deletedAt`.

- **Auth required**: Yes
- **Query Params**:
  - `beforeId` *(optional, integer)*: Only return messages with `id < beforeId`.
  - `limit` *(optional, integer, default `50`, max `100`)*: Maximum number of messages.
- **Response `200 OK`**:
  ```json
  {
    "messages": [
      {
        "id": 141,
        "channelId": 1,
        "conversationId": null,
        "senderId": 12,
        "senderUsername": "alice",
        "senderDisplayName": "Alice Nguyen",
        "content": "Hello from the Web client!",
        "replyTo": null,
        "attachments": [],
        "reactions": [
          { "emoji": "👍", "count": 2, "userIds": [1, 12], "me": true }
        ],
        "pinned": false,
        "createdAt": "2026-10-17T04:10:00Z",
        "editedAt": null,
        "deletedAt": null,
        "transport": "WS"
      }
    ],
    "hasMore": false
  }
  ```

#### `GET /api/channels/{channelId}/pins`
Returns all pinned messages (`pinned = TRUE` and `deleted_at IS NULL`) in the channel, newest pinned first.

- **Auth required**: Yes
- **Response `200 OK`**: `{ "messages": Message[] }`

#### `GET /api/search?guildId=<id>&channelId=<id>&q=<query>&limit=25`
Executes PostgreSQL full-text search (`to_tsvector('simple', content) @@ plainto_tsquery('simple', q)` with `ILIKE` fallback for short substrings) across accessible channels.

- **Auth required**: Yes
- **Query Params**:
  - `q` *(required, string)*: Search query string.
  - `guildId` *(optional, integer)*: Filter to a specific guild.
  - `channelId` *(optional, integer)*: Filter to a specific channel.
  - `limit` *(optional, integer, default `25`, max `50`)*.
- **Response `200 OK`**: `{ "messages": Message[] }`

#### `POST /api/channels/{channelId}/read`
Marks a channel as read up to `lastReadMessageId` in `channel_reads`.

- **Auth required**: Yes
- **Request Body**:
  ```json
  {
    "lastReadMessageId": 141
  }
  ```
- **Response `200 OK`**:
  ```json
  {
    "channelId": 1,
    "lastReadMessageId": 141
  }
  ```

---

### 3.5 File & Image Upload / Download (Neon `bytea`, Max 5 MB)

#### `PUT /api/files?channelId=<id>&dmId=<id>`
Uploads a raw binary file directly in the HTTP request body (no multipart boundary overhead) and stores it in PostgreSQL `attachments` (`data bytea`).

- **Auth required**: Yes
- **Query Params**:
  - `channelId` *(optional, integer)*: Target guild channel ID.
  - `dmId` *(optional, integer)*: Target direct conversation ID.
- **Request Headers**:
  - `X-File-Name`: URI-encoded UTF-8 filename (e.g., `encodeURIComponent("拓扑图_diagram.png")`).
  - `Content-Type`: Must be one of:
    - `image/png`, `image/jpeg`, `image/gif`, `image/webp` (`image/*`)
    - `application/pdf`
    - `application/zip`, `application/x-zip-compressed`
    - `text/plain`
- **Request Body**: Raw binary bytes (`<= 5,242,880` bytes / 5 MB).
- **Response `201 Created`**:
  ```json
  {
    "attachmentId": 42,
    "filename": "diagram.png",
    "contentType": "image/png",
    "sizeBytes": 184320,
    "url": "/api/files/42"
  }
  ```

#### `GET /api/files/{attachmentId}`
Streams the binary attachment from PostgreSQL `attachments`.

- **Auth required**: No (or optional `?token=<jwt>` query param so `<img src="/api/files/42">` and `<a href="/api/files/42">` work natively in the browser).
- **Response `200 OK` Headers**:
  - `Content-Type`: Stored MIME type (e.g. `image/png`).
  - `Content-Length`: Exact byte size.
  - `Cache-Control`: `public, max-age=31536000, immutable`
  - `Content-Disposition`: `inline; filename="<filename>"` for `image/*`, `application/pdf`, `text/plain`; `attachment; filename="<filename>"` for `application/zip`.

---

### 3.6 Friends & Direct Messages (DM)

#### `GET /api/friends`
Returns the authenticated user's accepted friends list (with live presence status) and pending incoming/outgoing friend requests.

- **Auth required**: Yes
- **Response `200 OK`**: `FriendsOverview` (`{ friends, pendingIncoming, pendingOutgoing }`)

#### `POST /api/friends/request`
Sends a friend request by `username` or `targetUserId`. Broadcasts `FRIEND_UPDATED` to the recipient if online.

- **Auth required**: Yes
- **Request Body**:
  ```json
  {
    "username": "bob"
  }
  ```
  *(or `{ "targetUserId": 15 }`)*
- **Response `201 Created`**: `{ "request": FriendRequest }`

#### `POST /api/friends/respond`
Accepts or rejects a pending friend request. When accepted, automatically ensures a `DirectConversation` exists between the two users and broadcasts `FRIEND_UPDATED`.

- **Auth required**: Yes
- **Request Body**:
  ```json
  {
    "requestId": 7,
    "action": "ACCEPT"
  }
  ```
  *(`action`: `"ACCEPT"` | `"REJECT"`)*
- **Response `200 OK`**: `{ "request": FriendRequest, "conversationId": 3 }`

#### `GET /api/dms`
Lists all Direct Message conversations (`direct_conversations`) involving the authenticated user, ordered by `updated_at DESC`.

- **Auth required**: Yes
- **Response `200 OK`**: `{ "conversations": DirectConversation[] }`

#### `POST /api/dms`
Gets or creates a 1-on-1 Direct Message conversation with `peerUserId`.

- **Auth required**: Yes
- **Request Body**:
  ```json
  {
    "peerUserId": 15
  }
  ```
- **Response `200 OK`**: `{ "conversation": DirectConversation }`

#### `GET /api/dms/{conversationId}/messages?beforeId=<id>&limit=50`
Cursor-paginated message history for a Direct Message conversation.

- **Auth required**: Yes (must be `user1_id` or `user2_id` of `conversationId`)
- **Response `200 OK`**: `{ "messages": Message[], "hasMore": boolean }`

---

### 3.7 Network Monitor & Health Endpoints

#### `GET /api/health`
Returns server health, database connectivity, active ports (`5000`, `5001`, `8080`, `8081`), and online session counts across WebSocket and TCP.

- **Auth required**: No
- **Response `200 OK`**: `HealthStatus`
  ```json
  {
    "status": "UP",
    "dbConnected": true,
    "ports": {
      "tcp": 5000,
      "udp": 5001,
      "http": 8080,
      "ws": 8081
    },
    "onlineSessions": {
      "total": 4,
      "ws": 3,
      "tcp": 1
    },
    "udpActive": true
  }
  ```

#### `POST /api/monitor/udp-probe`
Sends a real UDP datagram packet (`PING_UDP|<seq>|<clientTimestampMs>`) from the HTTP handler to the local `UdpTypingServer` socket (`:5001`) and awaits the UDP echo response so the Web Network Monitor Drawer can measure and compare real UDP socket RTT alongside WebSocket RTT.

- **Auth required**: Yes
- **Request Body**:
  ```json
  {
    "seq": 104,
    "clientTimestampMs": 1760673600123
  }
  ```
- **Response `200 OK`**:
  ```json
  {
    "seq": 104,
    "clientTimestampMs": 1760673600123,
    "serverTimestampMs": 1760673600125,
    "udpRttMs": 1.42,
    "udpPort": 5001,
    "rawDatagramSent": "PING_UDP|104|1760673600123",
    "rawDatagramReceived": "PONG_UDP|104|1760673600125",
    "transport": "UDP"
  }
  ```

---

## 4. WebSocket Real-Time Protocol (`ws://localhost:8081?token=<jwt>`)

### 4.1 JSON Frame Envelope

Every WebSocket text frame (both Client → Server and Server → Client) uses the `WsEnvelope<T>` structure:

```json
{
  "type": "EVENT_NAME",
  "rid": "req-9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "payload": { ... }
}
```

- `type` *(string, required)*: Uppercase event identifier.
- `rid` *(string, optional)*: Request correlation ID generated by the client when it wants an explicit `ACK` or `ERROR` matched to the request.
- `payload` *(object, required)*: Event-specific JSON object.

---

### 4.2 Client → Server Events

| Event `type` | `payload` Schema | Behavior & Side Effects |
|--------------|------------------|-------------------------|
| `SEND_MESSAGE` | `{ channelId: number, content: string, replyToId?: number \| null, attachmentIds?: number[] }` | Persists message in `messages` (`room_id = channelId`), links `attachmentIds`, broadcasts `MESSAGE_CREATED` to WS clients and `ROOM_MSG\|<channelId>\|<username>\|<content>` to legacy TCP clients. |
| `EDIT_MESSAGE` | `{ messageId: number, content: string }` | Verifies `sender_id == userId`, sets `edited_at = NOW()`, broadcasts `MESSAGE_UPDATED`. |
| `DELETE_MESSAGE` | `{ messageId: number }` | Verifies `sender_id == userId` or `OWNER`/`ADMIN` role, sets `deleted_at = NOW()`, broadcasts `MESSAGE_DELETED`. |
| `TOGGLE_REACTION` | `{ messageId: number, emoji: string }` | Inserts or deletes `(message_id, user_id, emoji)` in `message_reactions`, broadcasts `MESSAGE_REACTION_UPDATED`. |
| `PIN_MESSAGE` | `{ messageId: number, pinned: boolean }` | Updates `messages.pinned`, broadcasts `MESSAGE_PIN_UPDATED` to channel members. |
| `MARK_READ` | `{ channelId: number, lastReadMessageId: number }` | Upserts `(user_id, channel_id, last_read_message_id)` in `channel_reads`. |
| `SEND_DM` | `{ conversationId?: number, peerUserId?: number, content: string, replyToId?: number \| null, attachmentIds?: number[] }` | Persists DM in `messages` (`conversation_id`), broadcasts `DM_CREATED` to both participants (and bridges `PRIVATE_MSG` to TCP if recipient is on Swing client). |
| `TYPING_START` | `{ channelId?: number, conversationId?: number }` | Emits UDP loopback packet to `:5001` (when active) and broadcasts `TYPING_UPDATE` (`isTyping: true`, `transport: "UDP"` or `"WS"`) to peer members. |
| `TYPING_STOP` | `{ channelId?: number, conversationId?: number }` | Broadcasts `TYPING_UPDATE` (`isTyping: false`) to peer members. |
| `PRESENCE_UPDATE` | `{ status: 'ONLINE' \| 'IDLE' \| 'DND' \| 'OFFLINE', customStatus?: string \| null }` | Updates in-memory & DB presence, broadcasts `PRESENCE_UPDATED` to shared guild/DM peers. |
| `PING_RTT` | `{ seq: number, clientTimestampMs: number, transport?: 'WS' \| 'UDP' \| 'TCP' }` | Replies immediately with `PONG_RTT` containing `serverTimestampMs` and optional localhost UDP probe latency (`udpRttMs`). |
| `VOICE_JOIN` | `{ channelId: number, muted: boolean, cameraOn: boolean }` | Adds user to active WebRTC voice room `channelId` and broadcasts updated `VOICE_PARTICIPANTS` list. |
| `VOICE_LEAVE` | `{ channelId: number }` | Removes user from voice room `channelId` and broadcasts `VOICE_PARTICIPANTS`. |
| `VOICE_STATE` | `{ channelId: number, muted: boolean, cameraOn: boolean }` | Updates mute/camera state and broadcasts `VOICE_PARTICIPANTS`. |
| `SIGNAL_OFFER` | `{ channelId: number, targetUserId: number, sdp: RTCSessionDescriptionInit }` | Relays WebRTC SDP Offer directly to `targetUserId`'s WebSocket session(s). |
| `SIGNAL_ANSWER` | `{ channelId: number, targetUserId: number, sdp: RTCSessionDescriptionInit }` | Relays WebRTC SDP Answer directly to `targetUserId`'s WebSocket session(s). |
| `SIGNAL_ICE` | `{ channelId: number, targetUserId: number, candidate: RTCIceCandidateInit }` | Relays WebRTC ICE Candidate directly to `targetUserId`'s WebSocket session(s). |

---

### 4.3 Server → Client Events

| Event `type` | `payload` Schema | Description |
|--------------|------------------|-------------|
| `ACK` | `{ rid: string, status: 'OK', data?: unknown }` | Confirms successful processing of a client command with matching `rid`. |
| `ERROR` | `{ rid?: string, code: string, message: string }` | Reports validation, auth, or permission error for a WebSocket command. |
| `MESSAGE_CREATED` | `{ message: Message }` | Broadcast to all members of `message.channelId` when a new message arrives from either WS or TCP. |
| `MESSAGE_UPDATED` | `{ message: Message }` | Broadcast when a message's content is edited (`editedAt` populated). |
| `MESSAGE_DELETED` | `{ messageId: number, channelId?: number, conversationId?: number, deletedAt: string }` | Broadcast when a message is soft-deleted. |
| `MESSAGE_REACTION_UPDATED` | `{ messageId: number, channelId?: number, conversationId?: number, reactions: ReactionGroup[] }` | Broadcast when any reaction on `messageId` is toggled. |
| `MESSAGE_PIN_UPDATED` | `{ messageId: number, channelId: number, pinned: boolean, message: Message }` | Broadcast when a message is pinned or unpinned in a channel. |
| `DM_CREATED` | `{ conversationId: number, message: Message }` | Sent to both participants of a Direct Message conversation. |
| `TYPING_UPDATE` | `{ channelId?: number, conversationId?: number, userId: number, username: string, displayName: string, isTyping: boolean, transport: 'UDP' \| 'WS' \| 'TCP' }` | Real-time typing indicator showing which transport delivered the signal. |
| `PRESENCE_UPDATED` | `{ userId: number, username: string, displayName: string, status: PresenceStatus, customStatus?: string \| null, transport: 'WS' \| 'TCP' \| 'UDP' }` | Real-time user status update across guilds and friends list. |
| `GUILD_UPDATED` | `{ guild: Guild }` | Broadcast when guild metadata changes. |
| `CHANNEL_CREATED` | `{ guildId: number, channel: Channel }` | Broadcast to guild members when a new text or voice channel is created. |
| `MEMBER_JOINED` | `{ guildId: number, channelId?: number, member: ChannelMember }` | Broadcast when a user joins a guild/channel via invite or registration. |
| `MEMBER_LEFT` | `{ guildId: number, channelId?: number, userId: number }` | Broadcast when a user leaves a guild/channel. |
| `FRIEND_UPDATED` | `{ overview: FriendsOverview }` | Sent to affected users when a friend request is sent, accepted, or rejected. |
| `PONG_RTT` | `{ seq: number, clientTimestampMs: number, serverTimestampMs: number, udpRttMs?: number \| null, transport: 'WS' \| 'UDP' \| 'TCP' }` | Response to `PING_RTT` used by the Network Monitor live RTT chart. |
| `PROTOCOL_FRAME_LOG` | `{ direction: 'IN' \| 'OUT', transport: 'TCP' \| 'UDP' \| 'WS' \| 'HTTP', rawFrame: string, timestampMs: number }` | Live wire-frame telemetry emitted to the user's session so the Network Monitor UI can display raw TCP/UDP/WS protocol frames in real time. |
| `VOICE_PARTICIPANTS` | `{ channelId: number, participants: VoiceParticipant[] }` | Full list of active voice/video participants in `channelId`. |
| `SIGNAL_OFFER` | `{ channelId: number, fromUserId: number, fromDisplayName: string, sdp: RTCSessionDescriptionInit }` | Incoming WebRTC SDP Offer from a peer in `channelId`. |
| `SIGNAL_ANSWER` | `{ channelId: number, fromUserId: number, sdp: RTCSessionDescriptionInit }` | Incoming WebRTC SDP Answer from a peer in `channelId`. |
| `SIGNAL_ICE` | `{ channelId: number, fromUserId: number, candidate: RTCIceCandidateInit }` | Incoming WebRTC ICE Candidate from a peer in `channelId`. |

---

## 5. Cross-Protocol Bridge Matrix (TCP `:5000` ↔ WS `:8081` ↔ UDP `:5001`)

To showcase all 4 networking protocols for the Computer Networks (`Lập Trình Mạng Máy Tính`) course rubric, `SessionRegistry` bridges events transparently across transports:

| Action | Legacy Swing Client (`TCP :5000` / `UDP :5001`) | Web Client (`WS :8081` / `HTTP :8080`) |
|--------|-------------------------------------------------|----------------------------------------|
| **Channel Chat (`channelId == roomId`)** | `ROOM_MSG\|<roomId>\|<sender>\|<content>` | `MESSAGE_CREATED` (`transport: "TCP"` or `"WS"`) |
| **Direct Message (1-on-1)** | `PRIVATE_MSG\|<sender>\|<receiver>\|<content>` | `DM_CREATED` (`transport: "TCP"` or `"WS"`) |
| **Typing Notification** | UDP datagram `TYPING\|<roomId>\|<username>\|1` on `:5001` | `TYPING_UPDATE` (`transport: "UDP"`) |
| **Online Presence** | `USER_LIST\|u1,u2,...` & `STATUS_NOTIFY\|<user>\|<status>` | `PRESENCE_UPDATED` (`status: "ONLINE" \| "OFFLINE"`) |
| **Protocol Inspector** | Raw pipe-delimited frames (`CMD\|arg1\|arg2`) | `PROTOCOL_FRAME_LOG` events streamed to the Network Monitor UI |
