import type {
  Attachment,
  AuthResponse,
  Channel,
  ChannelType,
  DirectConversation,
  FriendRequest,
  Guild,
  MemberRole,
  Message,
  NetworkFrameLog,
  PresenceStatus,
  ReactionGroup,
  ReplyPreview,
  TransportKind,
  User,
} from '../types/api';

const DEFAULT_API_BASE =
  (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.VITE_API_BASE_URL ||
  'http://localhost:8080';

type FrameLogger = (entry: Omit<NetworkFrameLog, 'id' | 'timestampMs'>) => void;

function requireId(value: unknown, entity: string): number {
  const id = Number(value);
  if (!Number.isSafeInteger(id) || id <= 0) {
    throw new Error(`The server returned an invalid ${entity} id.`);
  }
  return id;
}

const AVATAR_PALETTE = ['#2563EB', '#0284C7', '#10B981', '#F59E0B', '#0EA5E9', '#F43F5E'];

export function pickAvatarColor(idOrName: number | string): string {
  const num =
    typeof idOrName === 'number'
      ? idOrName
      : Array.from(idOrName).reduce((acc, ch) => acc + ch.charCodeAt(0), 0);
  return AVATAR_PALETTE[Math.abs(num) % AVATAR_PALETTE.length];
}

export function normalizeUser(raw: Record<string, unknown>): User {
  const id = requireId(raw.id ?? raw.userId, 'user');
  const username = String(raw.username ?? '');
  if (!username.trim()) {
    throw new Error('The server returned an invalid user record.');
  }
  const displayName = String(raw.displayName ?? username);
  const status = (raw.status as PresenceStatus) ?? 'OFFLINE';
  return {
    id,
    username,
    displayName,
    avatarColor: (raw.avatarColor as string) || pickAvatarColor(id || username),
    status,
    customStatus: (raw.customStatus as string | undefined) ?? undefined,
    role: (raw.role as MemberRole | undefined) ?? 'MEMBER',
    online: status !== 'OFFLINE',
    transport: (raw.transport as TransportKind | undefined) ?? 'WS',
  };
}

export function normalizeAttachment(raw: Record<string, unknown>): Attachment {
  const id = requireId(raw.id ?? raw.attachmentId, 'attachment');
  const rawUrl = String(raw.url ?? `/api/files/${id}`);
  const url = rawUrl.startsWith('http') || rawUrl.startsWith('data:') ? rawUrl : `${DEFAULT_API_BASE}${rawUrl}`;
  return {
    id,
    filename: String(raw.filename ?? 'attachment.bin'),
    contentType: String(raw.contentType ?? 'application/octet-stream'),
    sizeBytes: Number(raw.sizeBytes ?? 0),
    url,
    dataUrl: raw.dataUrl as string | undefined,
  };
}

export function normalizeMessage(raw: Record<string, unknown>, fallbackChannelId?: number): Message {
  const id = requireId(raw.id, 'message');
  const channelId = requireId(raw.channelId ?? raw.conversationId ?? fallbackChannelId, 'channel');
  const createdAt = String(raw.createdAt ?? '');
  if (!Number.isFinite(Date.parse(createdAt))) throw new Error('The server returned an invalid message timestamp.');
  const senderId = Number(raw.senderId ?? 0);
  const senderName = String(
    raw.senderName ?? raw.senderDisplayName ?? raw.senderUsername ?? `User #${senderId}`,
  );
  const replyRaw = raw.replyTo as Record<string, unknown> | null | undefined;
  let replyTo: ReplyPreview | null = null;
  if (replyRaw && typeof replyRaw === 'object') {
    replyTo = {
      messageId: Number(replyRaw.messageId ?? replyRaw.id ?? 0),
      senderId: Number(replyRaw.senderId ?? 0),
      senderName: String(
        replyRaw.senderName ?? replyRaw.senderDisplayName ?? replyRaw.senderUsername ?? 'User',
      ),
      content: String(replyRaw.content ?? ''),
    };
  }

  const reactionsRaw = Array.isArray(raw.reactions) ? raw.reactions : [];
  const reactions: ReactionGroup[] = reactionsRaw.map((r: Record<string, unknown>) => ({
    emoji: String(r.emoji ?? '👍'),
    count: Number(r.count ?? (Array.isArray(r.userIds) ? r.userIds.length : 0)),
    userIds: Array.isArray(r.userIds) ? r.userIds.map(Number) : [],
    me: Boolean(r.me),
  }));

  const attachmentsRaw = Array.isArray(raw.attachments) ? raw.attachments : [];
  const attachments: Attachment[] = attachmentsRaw.map((a: Record<string, unknown>) =>
    normalizeAttachment(a),
  );

  return {
    id,
    channelId,
    senderId,
    senderName,
    senderAvatarColor: (raw.senderAvatarColor as string) || pickAvatarColor(senderId || senderName),
    content: String(raw.content ?? ''),
    createdAt,
    editedAt: (raw.editedAt as string | null | undefined) ?? undefined,
    deletedAt: (raw.deletedAt as string | null | undefined) ?? undefined,
    pinned: Boolean(raw.pinned),
    replyTo,
    reactions,
    attachments,
    transport: (raw.transport as TransportKind | undefined) ?? 'WS',
  };
}

export class ApiClient {
  private baseUrl: string;
  private token: string | null = null;
  private frameLogger: FrameLogger | null = null;

  constructor(baseUrl = DEFAULT_API_BASE) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
  }

  setToken(token: string | null): void {
    this.token = token;
  }

  getToken(): string | null {
    return this.token;
  }

  setFrameLogger(logger: FrameLogger | null): void {
    this.frameLogger = logger;
  }

  private emitFrame(direction: 'IN' | 'OUT', transport: TransportKind, rawFrame: string, summary: string): void {
    if (this.frameLogger) {
      this.frameLogger({ direction, transport, rawFrame, summary });
    }
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers ?? {});
    if (!headers.has('Content-Type') && !(init.body instanceof Blob) && !(init.body instanceof ArrayBuffer)) {
      headers.set('Content-Type', 'application/json');
    }
    if (this.token) {
      headers.set('Authorization', `Bearer ${this.token}`);
    }

    const method = init.method ?? 'GET';
    const url = `${this.baseUrl}${path}`;
    const safePath = path.split('?')[0];
    this.emitFrame('OUT', 'HTTP', `${method} ${safePath}`, `HTTP ${method} ${safePath}`);

    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), 15000);

    try {
      const response = await fetch(url, {
        ...init,
        headers,
        signal: controller.signal,
      });
      window.clearTimeout(timeoutId);

      const text = await response.text();
      let parsed: Record<string, unknown> = {};
      if (text) {
        try {
          parsed = JSON.parse(text) as Record<string, unknown>;
        } catch {
          parsed = { raw: text };
        }
      }

      this.emitFrame(
        'IN',
        'HTTP',
        `HTTP/${response.status} ${safePath}`,
        `HTTP ${response.status} ${safePath}`,
      );

      if (!response.ok) {
        const errObj = parsed.error as Record<string, unknown> | undefined;
        const message = String(errObj?.message ?? parsed.message ?? `HTTP Error ${response.status}`);
        throw new Error(message);
      }

      return parsed as unknown as T;
    } catch (err) {
      window.clearTimeout(timeoutId);
      throw err;
    }
  }

  async checkHealth(): Promise<boolean> {
    try {
      const res = await this.request<{ status?: string }>('/api/health');
      return res?.status === 'UP' || res?.status === 'DEGRADED';
    } catch {
      return false;
    }
  }

  async login(username: string, password: string): Promise<AuthResponse> {
    const res = await this.request<{ token: string; user: Record<string, unknown> }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
    return {
      token: res.token,
      user: normalizeUser(res.user),
    };
  }

  async register(username: string, password: string, displayName: string): Promise<AuthResponse> {
    const res = await this.request<{ token: string; user: Record<string, unknown> }>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password, displayName }),
    });
    return {
      token: res.token,
      user: normalizeUser(res.user),
    };
  }

  async getMe(): Promise<User> {
    const res = await this.request<{ user: Record<string, unknown> }>('/api/auth/me');
    return normalizeUser(res.user ?? (res as unknown as Record<string, unknown>));
  }

  async getGuilds(): Promise<Guild[]> {
    const res = await this.request<{ guilds: Record<string, unknown>[] }>('/api/guilds');
    const list = Array.isArray(res.guilds) ? res.guilds : [];
    return list.map((g) => ({
      id: Number(g.id),
      name: String(g.name),
      iconText: String(g.iconText ?? String(g.name ?? 'G').slice(0, 2).toUpperCase()),
    ownerId: requireId(g.ownerId, 'guild owner'),
      channels: Array.isArray(g.channels)
        ? g.channels.map((c: Record<string, unknown>, idx: number) => ({
            id: Number(c.id),
            guildId: Number(c.guildId ?? g.id),
            name: String(c.name),
            type: ((c.type as ChannelType) ?? 'text') === 'voice' ? 'voice' : 'text',
            topic: c.topic ? String(c.topic) : undefined,
            position: Number(c.position ?? idx),
            unreadCount: Number(c.unreadCount ?? 0),
          }))
        : [],
    }));
  }

  async createGuild(name: string, iconText?: string): Promise<Guild> {
    const res = await this.request<{ guild: Record<string, unknown> }>('/api/guilds', {
      method: 'POST',
      body: JSON.stringify({
        name,
        iconText: iconText || name.slice(0, 2).toUpperCase(),
      }),
    });
    const g = res.guild ?? (res as unknown as Record<string, unknown>);
    return {
      id: Number(g.id),
      name: String(g.name ?? name),
      iconText: String(g.iconText ?? (iconText || name.slice(0, 2).toUpperCase())),
      ownerId: requireId(g.ownerId, 'guild owner'),
      channels: Array.isArray(g.channels)
        ? g.channels.map((c: Record<string, unknown>, idx: number) => ({
            id: Number(c.id),
            guildId: Number(c.guildId ?? g.id),
            name: String(c.name),
            type: ((c.type as ChannelType) ?? 'text') === 'voice' ? 'voice' : 'text',
            topic: c.topic ? String(c.topic) : undefined,
            position: Number(c.position ?? idx),
            unreadCount: Number(c.unreadCount ?? 0),
          }))
        : [],
    };
  }

  async createChannel(guildId: number, name: string, type: ChannelType, topic?: string): Promise<Channel> {
    const res = await this.request<{ channel: Record<string, unknown> }>(
      `/api/guilds/${guildId}/channels`,
      {
        method: 'POST',
        body: JSON.stringify({ name, type, topic: topic ?? '' }),
      },
    );
    const c = res.channel ?? (res as unknown as Record<string, unknown>);
    return {
      id: Number(c.id),
      guildId: Number(c.guildId ?? guildId),
      name: String(c.name ?? name),
      type: ((c.type as ChannelType) ?? type) === 'voice' ? 'voice' : 'text',
      topic: c.topic ? String(c.topic) : topic,
      position: Number(c.position ?? 0),
      unreadCount: 0,
    };
  }

  async updateChannel(channelId: number, name: string, topic: string): Promise<Channel> {
    const res = await this.request<Record<string, unknown>>(`/api/channels/${channelId}`, {
      method: 'PATCH',
      body: JSON.stringify({ name, topic }),
    });
    const c = (res.channel as Record<string, unknown> | undefined) ?? res;
    const channelType = String(c.channelType ?? c.type ?? 'TEXT').toLowerCase();
    return {
      id: Number(c.id ?? channelId),
      guildId: Number(c.guildId ?? 0),
      name: String(c.name ?? name),
      type: channelType.includes('voice') ? 'voice' : 'text',
      topic: c.topic == null ? topic : String(c.topic),
      position: Number(c.position ?? 0),
      unreadCount: Number(c.unreadCount ?? 0),
    };
  }

  async deleteChannel(channelId: number): Promise<void> {
    await this.request<{ deleted: boolean; channelId: number }>(`/api/channels/${channelId}`, {
      method: 'DELETE',
    });
  }

  async createInvite(guildId: number): Promise<string> {
    const res = await this.request<{ code: string }>(`/api/guilds/${guildId}/invites`, {
      method: 'POST',
      body: JSON.stringify({ ttlMinutes: 1440, maxUses: 50 }),
    });
    return String(res.code);
  }

  async joinGuildByInvite(code: string): Promise<Guild> {
    const res = await this.request<{ guild: Record<string, unknown> }>(
      `/api/invites/${encodeURIComponent(code)}/join`,
      { method: 'POST' },
    );
    const g = res.guild ?? (res as unknown as Record<string, unknown>);
    return {
      id: Number(g.id),
      name: String(g.name),
      iconText: String(g.iconText ?? 'GD'),
      ownerId: requireId(g.ownerId, 'guild owner'),
      channels: Array.isArray(g.channels)
        ? g.channels.map((c: Record<string, unknown>, idx: number) => ({
            id: Number(c.id),
            guildId: Number(c.guildId ?? g.id),
            name: String(c.name),
            type: ((c.type as ChannelType) ?? 'text') === 'voice' ? 'voice' : 'text',
            topic: c.topic ? String(c.topic) : undefined,
            position: Number(c.position ?? idx),
            unreadCount: Number(c.unreadCount ?? 0),
          }))
        : [],
    };
  }

  async getChannelMessages(channelId: number): Promise<Message[]> {
    const res = await this.request<{ messages: Record<string, unknown>[] }>(
      `/api/channels/${channelId}/messages?limit=50`,
    );
    const list = Array.isArray(res.messages) ? res.messages : [];
    return list.map((m) => normalizeMessage(m, channelId));
  }

  async markChannelRead(channelId: number, lastReadMessageId: number): Promise<{ unreadCount?: number }> {
    return this.request<{ unreadCount?: number }>(`/api/channels/${channelId}/read`, {
      method: 'POST',
      body: JSON.stringify({ lastReadMessageId }),
    });
  }

  async getChannelPins(channelId: number): Promise<Message[]> {
    const res = await this.request<{ messages: Record<string, unknown>[] }>(
      `/api/channels/${channelId}/pins`,
    );
    const list = Array.isArray(res.messages) ? res.messages : [];
    return list.map((m) => normalizeMessage(m, channelId));
  }

  async getChannelMembers(channelId: number): Promise<User[]> {
    const res = await this.request<{ members: Record<string, unknown>[] }>(
      `/api/channels/${channelId}/members`,
    );
    const list = Array.isArray(res.members) ? res.members : [];
    return list.map((m) => normalizeUser(m));
  }

  async updateMemberRole(channelId: number, userId: number, role: MemberRole): Promise<void> {
    await this.request(`/api/channels/${channelId}/members/${userId}/role`, {
      method: 'PATCH',
      body: JSON.stringify({ role }),
    });
  }

  async searchMessages(query: string, guildId?: number, channelId?: number): Promise<Message[]> {
    const params = new URLSearchParams({ q: query });
    if (guildId) params.set('guildId', String(guildId));
    if (channelId) params.set('channelId', String(channelId));
    const res = await this.request<{ messages: Record<string, unknown>[] }>(
      `/api/search?${params.toString()}`,
    );
    const list = Array.isArray(res.messages) ? res.messages : [];
    return list.map((m) => normalizeMessage(m, channelId));
  }

  async uploadFile(file: File, channelId?: number, dmId?: number): Promise<Attachment> {
    const params = new URLSearchParams();
    if (channelId) params.set('channelId', String(channelId));
    if (dmId) params.set('dmId', String(dmId));
    const query = params.toString() ? `?${params.toString()}` : '';
    const buffer = await file.arrayBuffer();

    const res = await this.request<Record<string, unknown>>(`/api/files${query}`, {
      method: 'PUT',
      headers: {
        'Content-Type': file.type || 'application/octet-stream',
        'X-File-Name': encodeURIComponent(file.name),
      },
      body: buffer,
    });
    return normalizeAttachment(res);
  }

  async getAttachmentObjectUrl(attachmentId: number): Promise<string> {
    const path = `/api/files/${attachmentId}`;
    const headers = new Headers();
    if (this.token) headers.set('Authorization', `Bearer ${this.token}`);
    this.emitFrame('OUT', 'HTTP', `GET ${path}`, `HTTP GET ${path}`);
    const response = await fetch(`${this.baseUrl}${path}`, { headers });
    if (!response.ok) {
      this.emitFrame('IN', 'HTTP', `GET ${path} -> ${response.status}`, `HTTP ${response.status} ${path}`);
      throw new Error(response.status === 401 || response.status === 403
        ? 'You do not have access to this file.'
        : 'The file could not be loaded.');
    }
    const blob = await response.blob();
    this.emitFrame('IN', 'HTTP', `GET ${path} -> ${response.status} ${blob.size} bytes`, `HTTP ${response.status} ${path}`);
    return URL.createObjectURL(blob);
  }

  async getFriends(currentUserId: number): Promise<{
    friends: User[];
    requests: FriendRequest[];
  }> {
    const res = await this.request<{
      friends?: Record<string, unknown>[];
      pendingIncoming?: Record<string, unknown>[];
      pendingOutgoing?: Record<string, unknown>[];
    }>('/api/friends');

    const friends = Array.isArray(res.friends) ? res.friends.map(normalizeUser) : [];
    const incoming: FriendRequest[] = Array.isArray(res.pendingIncoming)
      ? res.pendingIncoming.map((r) => ({
          id: Number(r.id),
          user: normalizeUser((r.fromUser as Record<string, unknown>) ?? r),
          direction: 'INCOMING',
          createdAt: String(r.createdAt ?? ''),
        }))
      : [];
    const outgoing: FriendRequest[] = Array.isArray(res.pendingOutgoing)
      ? res.pendingOutgoing.map((r) => {
          const peer =
            Number((r.fromUser as Record<string, unknown>)?.id) === currentUserId
              ? (r.toUser as Record<string, unknown>)
              : (r.toUser as Record<string, unknown>) ?? (r.fromUser as Record<string, unknown>);
          return {
            id: Number(r.id),
            user: normalizeUser(peer ?? r),
            direction: 'OUTGOING',
            createdAt: String(r.createdAt ?? ''),
          };
        })
      : [];

    return {
      friends,
      requests: [...incoming, ...outgoing],
    };
  }

  async sendFriendRequest(usernameOrId: string): Promise<void> {
    const body = /^\d+$/.test(usernameOrId.trim())
      ? { targetUserId: Number(usernameOrId.trim()) }
      : { username: usernameOrId.trim() };
    await this.request('/api/friends/request', {
      method: 'POST',
      body: JSON.stringify(body),
    });
  }

  async respondFriendRequest(requestId: number, accept: boolean): Promise<void> {
    await this.request('/api/friends/respond', {
      method: 'POST',
      body: JSON.stringify({
        requestId,
        action: accept ? 'ACCEPT' : 'REJECT',
      }),
    });
  }

  async getDms(): Promise<DirectConversation[]> {
    const res = await this.request<{ conversations: Record<string, unknown>[] }>('/api/dms');
    const list = Array.isArray(res.conversations) ? res.conversations : [];
    return list.map((c) => {
      const lastMsg = c.lastMessage as Record<string, unknown> | null | undefined;
      return {
        id: Number(c.id),
        peerUser: normalizeUser((c.peerUser as Record<string, unknown>) ?? {}),
        lastMessagePreview: lastMsg
          ? String(lastMsg.content ?? '')
          : (c.lastMessagePreview as string | undefined),
        unreadCount: Number(c.unreadCount ?? 0),
      };
    });
  }

  async openDm(peerUserId: number): Promise<DirectConversation> {
    const res = await this.request<{ conversation: Record<string, unknown> }>('/api/dms', {
      method: 'POST',
      body: JSON.stringify({ peerUserId }),
    });
    const c = res.conversation ?? (res as unknown as Record<string, unknown>);
    return {
      id: Number(c.id),
      peerUser: normalizeUser((c.peerUser as Record<string, unknown>) ?? {}),
      lastMessagePreview: c.lastMessagePreview as string | undefined,
      unreadCount: Number(c.unreadCount ?? 0),
    };
  }

  async getDmMessages(conversationId: number): Promise<Message[]> {
    const res = await this.request<{ messages: Record<string, unknown>[] }>(
      `/api/dms/${conversationId}/messages?limit=50`,
    );
    const list = Array.isArray(res.messages) ? res.messages : [];
    return list.map((m) => normalizeMessage(m, conversationId));
  }

  async markDmRead(conversationId: number, lastReadMessageId: number): Promise<{ unreadCount: number }> {
    const res = await this.request<{ unreadCount?: number }>(`/api/dms/${conversationId}/read`, {
      method: 'POST',
      body: JSON.stringify({ lastReadMessageId }),
    });
    return { unreadCount: Number(res.unreadCount ?? 0) };
  }

  async probeUdpRtt(seq: number, clientTimestampMs: number): Promise<{
    udpRttMs: number | null;
    rawDatagramSent: string;
    rawDatagramReceived: string | null;
  }> {
    const res = await this.request<{
      udpRttMs?: number;
      rawDatagramSent?: string;
      rawDatagramReceived?: string;
    }>('/api/monitor/udp-probe', {
      method: 'POST',
      body: JSON.stringify({ seq, clientTimestampMs }),
    });
    return {
      udpRttMs: typeof res.udpRttMs === 'number' && Number.isFinite(res.udpRttMs) && res.udpRttMs >= 0
        ? res.udpRttMs
        : null,
      rawDatagramSent: typeof res.rawDatagramSent === 'string' ? res.rawDatagramSent : '',
      rawDatagramReceived: typeof res.rawDatagramReceived === 'string' ? res.rawDatagramReceived : null,
    };
  }
}

export const apiClient = new ApiClient();
