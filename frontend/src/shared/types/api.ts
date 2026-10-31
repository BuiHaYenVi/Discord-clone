export type PresenceStatus = 'ONLINE' | 'IDLE' | 'DND' | 'OFFLINE';
export type MemberRole = 'OWNER' | 'ADMIN' | 'MEMBER';
export type ChannelType = 'text' | 'voice';
export type TransportKind = 'TCP' | 'UDP' | 'WS' | 'HTTP' | 'UDP_FALLBACK';

export interface User {
  id: number;
  username: string;
  displayName: string;
  avatarColor?: string;
  avatarUrl?: string;
  status?: PresenceStatus;
  customStatus?: string;
  role?: MemberRole;
  online?: boolean;
  transport?: TransportKind;
}

export interface Channel {
  id: number;
  guildId: number;
  name: string;
  type: ChannelType;
  topic?: string;
  position: number;
  unreadCount?: number;
}

export interface Guild {
  id: number;
  name: string;
  iconText: string;
  ownerId: number;
  channels: Channel[];
}

export interface Attachment {
  id: number;
  filename: string;
  contentType: string;
  sizeBytes: number;
  url: string;
  dataUrl?: string;
}

export interface ReactionGroup {
  emoji: string;
  count: number;
  userIds: number[];
  me?: boolean;
}

export interface ReplyPreview {
  messageId: number;
  senderId: number;
  senderName: string;
  content: string;
}

export interface Message {
  id: number;
  channelId: number;
  senderId: number;
  senderName: string;
  senderAvatarColor?: string;
  content: string;
  createdAt: string;
  editedAt?: string;
  deletedAt?: string;
  pinned?: boolean;
  replyTo?: ReplyPreview | null;
  reactions: ReactionGroup[];
  attachments: Attachment[];
  transport?: TransportKind;
}

export interface DirectConversation {
  id: number;
  peerUser: User;
  lastMessagePreview?: string;
  unreadCount?: number;
}

export interface FriendRequest {
  id: number;
  user: User;
  direction: 'INCOMING' | 'OUTGOING';
  createdAt: string;
}

export interface VoiceParticipant {
  userId: number;
  username: string;
  displayName: string;
  avatarColor: string;
  channelId: number;
  muted: boolean;
  deafened?: boolean;
  cameraOn: boolean;
  speaking?: boolean;
}

export interface RttSample {
  seq: number;
  timestampMs: number;
  wsRttMs: number;
  tcpRttMs: number;
  udpRttMs: number | null;
  udpFallback: boolean;
}

export interface NetworkFrameLog {
  id: string;
  direction: 'IN' | 'OUT';
  transport: TransportKind;
  rawFrame: string;
  summary: string;
  timestampMs: number;
}

export interface ToastItem {
  id: string;
  type: 'info' | 'success' | 'warning' | 'error';
  title: string;
  message?: string;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface WsEnvelope<T = unknown> {
  type: string;
  rid?: string;
  payload: T;
}
