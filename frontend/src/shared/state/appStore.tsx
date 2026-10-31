import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { apiClient, normalizeMessage, pickAvatarColor } from '../api/apiClient';
import { wsClient } from '../ws/wsClient';
import { userFacingError } from '../ui/userFacingError';
import type {
  Attachment,
  Channel,
  ChannelType,
  DirectConversation,
  FriendRequest,
  Guild,
  MemberRole,
  Message,
  NetworkFrameLog,
  PresenceStatus,
  RttSample,
  ToastItem,
  TransportKind,
  User,
  VoiceParticipant,
} from '../types/api';

/* AppStore Context Interface */

export interface AppStoreValue {
  // State
  currentUser: User | null;
  token: string | null;
  isAuthenticated: boolean;
  connectionMode: 'live';
  isConnected: boolean;
  theme: 'light' | 'dark';
  locale: 'vi' | 'en';
  viewMode: 'guild' | 'dm' | 'friends';
  guilds: Guild[];
  channelMembersById: Record<number, User[] | null>;
  activeGuildId: number;
  activeChannelId: number;
  activeGuild: Guild | null;
  activeChannel: Channel | null;
  messages: Message[];
  pinnedMessages: Message[];
  searchResults: Message[];
  searchQuery: string;
  members: User[];
  typingUsers: { userId: number; displayName: string; transport: TransportKind }[];
  replyingTo: Message | null;
  editingMessage: Message | null;
  friends: User[];
  friendRequests: FriendRequest[];
  dmConversations: DirectConversation[];
  activeDmId: number | null;
  dmMessages: Message[];
  activeVoiceChannelId: number | null;
  voiceParticipants: VoiceParticipant[];
  voiceSignals: { id: string; type: 'SIGNAL_OFFER' | 'SIGNAL_ANSWER' | 'SIGNAL_ICE'; payload: Record<string, unknown> }[];
  localVoiceState: {
    muted: boolean;
    deafened: boolean;
    cameraOn: boolean;
    screenSharing: boolean;
  };
  rttSamples: RttSample[];
  frameLogs: NetworkFrameLog[];
  isMonitorOpen: boolean;
  isSettingsOpen: boolean;
  isCommandPaletteOpen: boolean;
  isMemberSidebarOpen: boolean;
  mobileDrawerOpen: 'none' | 'channels' | 'members';
  toasts: ToastItem[];

  // Actions
  login(username: string, password: string): Promise<void>;
  register(username: string, password: string, displayName: string): Promise<void>;
  logout(): void;
  setTheme(theme: 'light' | 'dark'): void;
  toggleTheme(): void;
  setLocale(locale: 'vi' | 'en'): void;
  setViewMode(mode: 'guild' | 'dm' | 'friends'): void;
  selectGuild(guildId: number): Promise<void>;
  selectChannel(channelId: number): void;
  createGuild(name: string, iconText?: string): Promise<void>;
  createChannel(guildId: number, name: string, type: ChannelType, topic?: string): Promise<void>;
  loadChannelMembers(channelId: number): Promise<void>;
  updateChannel(channelId: number, name: string, topic: string): Promise<void>;
  deleteChannel(channelId: number): Promise<void>;
  createInvite(guildId: number): Promise<string>;
  joinGuildByInvite(code: string): Promise<void>;
  sendMessage(content: string, attachments?: Attachment[]): Promise<void>;
  editMessage(messageId: number, content: string): Promise<void>;
  deleteMessage(messageId: number): Promise<void>;
  toggleReaction(messageId: number, emoji: string): Promise<void>;
  togglePinMessage(messageId: number, pinned: boolean): Promise<void>;
  setReplyingTo(msg: Message | null): void;
  setEditingMessage(msg: Message | null): void;
  searchChannelMessages(query: string): Promise<void>;
  uploadFile(file: File): Promise<Attachment>;
  notifyTyping(isTyping: boolean): void;
  updatePresence(status: PresenceStatus, customStatus?: string): void;
  updateMemberRole(userId: number, role: MemberRole): Promise<void>;
  sendFriendRequest(usernameOrId: string): Promise<void>;
  respondFriendRequest(requestId: number, accept: boolean): Promise<void>;
  openDmWithUser(userId: number): Promise<void>;
  selectDm(dmId: number): Promise<void>;
  sendDmMessage(content: string, attachments?: Attachment[]): Promise<void>;
  joinVoiceChannel(channelId: number): void;
  leaveVoiceChannel(): void;
  updateLocalVoiceState(
    partial: Partial<{
      muted: boolean;
      deafened: boolean;
      cameraOn: boolean;
      screenSharing: boolean;
    }>,
  ): void;
  sendVoiceSignal(
    type: 'SIGNAL_OFFER' | 'SIGNAL_ANSWER' | 'SIGNAL_ICE',
    targetUserId: number,
    data: Record<string, unknown>,
  ): boolean;
  consumeVoiceSignal(id: string): void;
  triggerRttProbe(): Promise<void>;
  clearFrameLogs(): void;
  setMonitorOpen(open: boolean): void;
  setSettingsOpen(open: boolean): void;
  setCommandPaletteOpen(open: boolean): void;
  setMemberSidebarOpen(open: boolean): void;
  setMobileDrawerOpen(drawer: 'none' | 'channels' | 'members'): void;
  addToast(type: ToastItem['type'], title: string, message?: string): void;
  dismissToast(id: string): void;
}

const AppStoreContext = createContext<AppStoreValue | null>(null);

function mergeMessageHistory(existing: Message[], refreshed: Message[]): Message[] {
  const merged = new Map<number, Message>();
  existing.forEach((message) => merged.set(message.id, message));
  refreshed.forEach((message) => merged.set(message.id, message));
  return [...merged.values()].sort((left, right) => left.id - right.id).slice(-100);
}

export const AppStoreProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  // Authentication & Session
  const [currentUser, setCurrentUser] = useState<User | null>(() => {
    try {
      const savedUser = localStorage.getItem('oceanic_user');
      return savedUser ? (JSON.parse(savedUser) as User) : null;
    } catch {
      return null;
    }
  });
  const [token, setToken] = useState<string | null>(() => {
    try {
      return localStorage.getItem('oceanic_token');
    } catch {
      return null;
    }
  });
  const connectionMode = 'live' as const;
  const [isConnected, setIsConnected] = useState<boolean>(false);
  const sessionRestoreStartedRef = useRef(false);

  // Appearance & Navigation
  const [theme, setThemeState] = useState<'light' | 'dark'>(() => {
    const saved = localStorage.getItem('oceanic_theme');
    return saved === 'dark' ? 'dark' : 'light';
  });
  const [locale, setLocaleState] = useState<'vi' | 'en'>(() => {
    const saved = localStorage.getItem('oceanic_locale');
    return saved === 'en' ? 'en' : 'vi';
  });
  const [viewMode, setViewMode] = useState<'guild' | 'dm' | 'friends'>('guild');

  // Guilds, Channels & Messages
  const [guilds, setGuilds] = useState<Guild[]>([]);
  const [activeGuildId, setActiveGuildId] = useState<number>(0);
  const [activeChannelId, setActiveChannelId] = useState<number>(0);
  const [messagesByChannel, setMessagesByChannel] = useState<Record<number, Message[]>>({});
  const [searchResults, setSearchResults] = useState<Message[]>([]);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [members, setMembers] = useState<User[]>([]);
  const [channelMembersById, setChannelMembersById] = useState<Record<number, User[] | null>>({});
  const [typingUsers, setTypingUsers] = useState<
    { userId: number; displayName: string; transport: TransportKind }[]
  >([]);
  const [replyingTo, setReplyingTo] = useState<Message | null>(null);
  const [editingMessage, setEditingMessage] = useState<Message | null>(null);

  // Friends & Direct Messages
  const [friends, setFriends] = useState<User[]>([]);
  const [friendRequests, setFriendRequests] = useState<FriendRequest[]>([]);
  const [dmConversations, setDmConversations] = useState<DirectConversation[]>([]);
  const [activeDmId, setActiveDmId] = useState<number | null>(null);
  const [dmMessagesByConv, setDmMessagesByConv] = useState<Record<number, Message[]>>({});

  // Voice / WebRTC Stage
  const [activeVoiceChannelId, setActiveVoiceChannelId] = useState<number | null>(null);
  const [voiceParticipants, setVoiceParticipants] = useState<VoiceParticipant[]>([]);
  const [voiceSignals, setVoiceSignals] = useState<AppStoreValue['voiceSignals']>([]);
  const [localVoiceState, setLocalVoiceState] = useState({
    muted: false,
    deafened: false,
    cameraOn: false,
    screenSharing: false,
  });

  // Telemetry & UI Panels
  const [rttSamples, setRttSamples] = useState<RttSample[]>([]);
  const [frameLogs, setFrameLogs] = useState<NetworkFrameLog[]>([]);
  const [isMonitorOpen, setMonitorOpen] = useState<boolean>(false);
  const [isSettingsOpen, setSettingsOpen] = useState<boolean>(false);
  const [isCommandPaletteOpen, setCommandPaletteOpen] = useState<boolean>(false);
  const [isMemberSidebarOpen, setMemberSidebarOpen] = useState<boolean>(false);
  const [mobileDrawerOpen, setMobileDrawerOpen] = useState<'none' | 'channels' | 'members'>('none');
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const activeViewRef = useRef(viewMode);
  const activeChannelRef = useRef(activeChannelId);
  const activeDmRef = useRef(activeDmId);
  const sessionGenerationRef = useRef(0);
  const sessionUserIdRef = useRef<number | null>(currentUser?.id ?? null);

  useEffect(() => {
    activeViewRef.current = viewMode;
    activeChannelRef.current = activeChannelId;
    activeDmRef.current = activeDmId;
  }, [viewMode, activeChannelId, activeDmId]);

  useEffect(() => {
    sessionUserIdRef.current = currentUser?.id ?? null;
  }, [currentUser?.id]);

  const rttSeqRef = useRef<number>(1);
  const channelMembersLoadingRef = useRef(new Set<number>());
  const searchRequestIdRef = useRef(0);
  const channelLoadRequestIdRef = useRef(0);
  const hasConnectedOnceRef = useRef(false);

  // Sync theme to document root
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('oceanic_theme', theme);
  }, [theme]);

  useEffect(() => {
    document.documentElement.setAttribute('lang', locale);
    localStorage.setItem('oceanic_locale', locale);
  }, [locale]);

  const appendFrameLog = useCallback(
    (entry: Omit<NetworkFrameLog, 'id' | 'timestampMs'>) => {
      const item: NetworkFrameLog = {
        ...entry,
        id: `frm-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        timestampMs: Date.now(),
      };
      setFrameLogs((prev) => [item, ...prev.slice(0, 199)]);
    },
    [],
  );

  const dismissToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const addToast = useCallback(
    (type: ToastItem['type'], title: string, message?: string) => {
      const id = `toast-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
      setToasts((prev) => [...prev, { id, type, title, message }]);
      window.setTimeout(() => {
        dismissToast(id);
      }, 4200);
    },
    [dismissToast],
  );

  // Register frame loggers on apiClient and wsClient
  useEffect(() => {
    apiClient.setFrameLogger(appendFrameLog);
    wsClient.setFrameLogger(appendFrameLog);
    return () => {
      apiClient.setFrameLogger(null);
      wsClient.setFrameLogger(null);
    };
  }, [appendFrameLog]);

  // Listen to incoming WebSocket frames when in live mode
  useEffect(() => {
    const unsubEvent = wsClient.onEvent((envelope) => {
      const { type, payload } = envelope;
      if (!payload) return;

      switch (type) {
        case 'MESSAGE_CREATED': {
          const rawMsg = (payload.message as Record<string, unknown>) ?? payload;
          const msg = normalizeMessage(rawMsg, activeChannelId);
          setMessagesByChannel((prev) => {
            const list = prev[msg.channelId] ?? [];
            if (list.some((m) => m.id === msg.id)) return prev;
            return { ...prev, [msg.channelId]: [...list, msg] };
          });
          const isIncoming = msg.senderId !== currentUser?.id;
          const isVisibleActiveChannel = viewMode === 'guild'
            && msg.channelId === activeChannelId
            && document.visibilityState === 'visible';
          if (isIncoming && !isVisibleActiveChannel) {
            setGuilds((prev) => prev.map((guild) => ({
              ...guild,
              channels: guild.channels.map((channel) => channel.id === msg.channelId
                ? { ...channel, unreadCount: (channel.unreadCount ?? 0) + 1 }
                : channel),
            })));
            if (currentUser?.status !== 'DND') {
              addToast('info', 'Tin nhắn mới', `${msg.senderName}: ${msg.content.slice(0, 100)}`);
            }
          } else if (isIncoming && isVisibleActiveChannel) {
            void apiClient.markChannelRead(msg.channelId, msg.id).then((readState) => {
              if (activeViewRef.current !== 'guild' || activeChannelRef.current !== msg.channelId
                  || document.visibilityState !== 'visible') return;
              setGuilds((prev) => prev.map((guild) => ({
                ...guild,
                channels: guild.channels.map((channel) => channel.id === msg.channelId
                  ? { ...channel, unreadCount: readState.unreadCount ?? 0 }
                  : channel),
              })));
            }).catch(() => undefined);
          }
          break;
        }
        case 'READ_UPDATED': {
          const userId = Number(payload.userId);
          const channelId = Number(payload.channelId ?? payload.roomId);
          const unreadCount = Number(payload.unreadCount);
          if (userId !== currentUser?.id || !Number.isSafeInteger(channelId) || channelId <= 0
              || !Number.isFinite(unreadCount) || unreadCount < 0) break;
          setGuilds((prev) => prev.map((guild) => ({
            ...guild,
            channels: guild.channels.map((channel) => channel.id === channelId
              ? { ...channel, unreadCount }
              : channel),
          })));
          break;
        }
        case 'MESSAGE_UPDATED': {
          const rawMsg = (payload.message as Record<string, unknown>) ?? payload;
          const msg = normalizeMessage(rawMsg, activeChannelId);
          setMessagesByChannel((prev) => {
            const list = prev[msg.channelId] ?? [];
            return {
              ...prev,
              [msg.channelId]: list.map((m) => (m.id === msg.id ? msg : m)),
            };
          });
          break;
        }
        case 'MESSAGE_DELETED': {
          const messageId = Number(payload.messageId);
          const channelId = Number(payload.channelId ?? activeChannelId);
          const deletedAt = String(payload.deletedAt ?? new Date().toISOString());
          setMessagesByChannel((prev) => {
            const list = prev[channelId] ?? [];
            return {
              ...prev,
              [channelId]: list.map((m) => (m.id === messageId ? { ...m, deletedAt } : m)),
            };
          });
          break;
        }
        case 'MESSAGE_REACTION_UPDATED': {
          const messageId = Number(payload.messageId);
          const channelId = Number(payload.channelId ?? activeChannelId);
          const reactionsRaw = Array.isArray(payload.reactions) ? payload.reactions : [];
          setMessagesByChannel((prev) => {
            const list = prev[channelId] ?? [];
            return {
              ...prev,
              [channelId]: list.map((m) =>
                m.id === messageId
                  ? {
                      ...m,
                      reactions: reactionsRaw.map((r: Record<string, unknown>) => ({
                        emoji: String(r.emoji),
                        count: Number(r.count ?? (Array.isArray(r.userIds) ? r.userIds.length : 0)),
                        userIds: Array.isArray(r.userIds) ? r.userIds.map(Number) : [],
                        me: Boolean(r.me),
                      })),
                    }
                  : m,
              ),
            };
          });
          break;
        }
        case 'DM_REACTION_UPDATED': {
          const conversationId = Number(payload.conversationId);
          const messageId = Number(payload.messageId);
          const reactionsRaw = Array.isArray(payload.reactions) ? payload.reactions : [];
          if (!Number.isSafeInteger(conversationId) || conversationId <= 0 || !Number.isSafeInteger(messageId) || messageId <= 0) break;
          setDmMessagesByConv((prev) => {
            const messages = prev[conversationId] ?? [];
            return {
              ...prev,
              [conversationId]: messages.map((message) => message.id === messageId
                ? {
                    ...message,
                    reactions: reactionsRaw.map((reaction: Record<string, unknown>) => ({
                      emoji: String(reaction.emoji),
                      count: Number(reaction.count ?? (Array.isArray(reaction.userIds) ? reaction.userIds.length : 0)),
                      userIds: Array.isArray(reaction.userIds) ? reaction.userIds.map(Number) : [],
                      me: Boolean(reaction.me),
                    })),
                  }
                : message),
            };
          });
          break;
        }
        case 'MESSAGE_PIN_UPDATED': {
          const messageId = Number(payload.messageId);
          const channelId = Number(payload.channelId ?? activeChannelId);
          const pinned = Boolean(payload.pinned);
          setMessagesByChannel((prev) => {
            const list = prev[channelId] ?? [];
            return {
              ...prev,
              [channelId]: list.map((m) => (m.id === messageId ? { ...m, pinned } : m)),
            };
          });
          break;
        }
        case 'DM_CREATED': {
          const convId = Number(payload.conversationId ?? activeDmId);
          if (!Number.isSafeInteger(convId) || convId <= 0) break;
          const rawMsg = (payload.message as Record<string, unknown>) ?? payload;
          const msg = normalizeMessage(rawMsg, convId);
          setDmMessagesByConv((prev) => {
            const list = prev[convId] ?? [];
            if (list.some((m) => m.id === msg.id)) return prev;
            return { ...prev, [convId]: [...list, msg] };
          });
          const isIncoming = msg.senderId !== currentUser?.id;
          const isVisibleActiveDm = viewMode === 'dm'
            && activeDmId === convId
            && document.visibilityState === 'visible';
          setDmConversations((prev) => prev.map((conversation) => conversation.id === convId
            ? {
                ...conversation,
                lastMessagePreview: msg.content,
                unreadCount: isIncoming && !isVisibleActiveDm
                  ? (conversation.unreadCount ?? 0) + 1
                  : conversation.unreadCount,
              }
            : conversation));
          if (isIncoming && !isVisibleActiveDm && currentUser?.status !== 'DND') {
            addToast('info', 'Tin nhắn mới', `${msg.senderName}: ${msg.content.slice(0, 100)}`);
          } else if (isIncoming && isVisibleActiveDm) {
            void apiClient.markDmRead(convId, msg.id).then((readState) => {
              setDmConversations((prev) => prev.map((conversation) => conversation.id === convId
                ? { ...conversation, unreadCount: readState.unreadCount }
                : conversation));
            }).catch(() => undefined);
          }
          break;
        }
        case 'TYPING_UPDATE': {
          const userId = Number(payload.userId);
          const displayName = String(payload.displayName ?? payload.username ?? 'Member');
          const isTyping = Boolean(payload.isTyping);
          const transport = (payload.transport as TransportKind) ?? 'UDP';
          setTypingUsers((prev) => {
            const filtered = prev.filter((u) => u.userId !== userId);
            return isTyping ? [...filtered, { userId, displayName, transport }] : filtered;
          });
          break;
        }
        case 'PRESENCE_UPDATED': {
          const userId = Number(payload.userId);
          const status = (payload.status as PresenceStatus) ?? 'OFFLINE';
          const hasCustomStatus = Object.prototype.hasOwnProperty.call(payload, 'customStatus');
          const customStatus = hasCustomStatus
            ? (payload.customStatus == null ? '' : String(payload.customStatus))
            : undefined;
          const updateUser = (person: User) => person.id === userId
            ? {
                ...person,
                status,
                ...(hasCustomStatus ? { customStatus } : {}),
                online: status !== 'OFFLINE',
              }
            : person;
          setCurrentUser((prev) => prev ? updateUser(prev) : prev);
          setMembers((prev) => prev.map(updateUser));
          setFriends((prev) => prev.map(updateUser));
          setDmConversations((prev) => prev.map((conversation) => ({
            ...conversation,
            peerUser: updateUser(conversation.peerUser),
          })));
          setChannelMembersById((prev) => Object.fromEntries(Object.entries(prev).map(([id, roster]) => [
            id,
            Array.isArray(roster) ? roster.map(updateUser) : roster,
          ])));
          break;
        }
        case 'MEMBER_ROLE_UPDATED': {
          const roomId = Number(payload.roomId ?? payload.channelId);
          const userId = Number(payload.userId);
          const role = String(payload.role ?? '').toUpperCase() as MemberRole;
          if (!roomId || !userId || !(['OWNER', 'ADMIN', 'MEMBER'] as string[]).includes(role)) break;
          const applyRole = (roster: User[]) => roster.map((member) => {
            if (member.id === userId) return { ...member, role };
            if (role === 'OWNER' && member.role === 'OWNER') return { ...member, role: 'ADMIN' as const };
            return member;
          });
          setChannelMembersById((prev) => {
            const roster = prev[roomId];
            return Array.isArray(roster) ? { ...prev, [roomId]: applyRole(roster) } : prev;
          });
          if (roomId === activeChannelId) {
            setMembers((prev) => applyRole(prev));
          }
          break;
        }
        case 'CHANNEL_CREATED': {
          const guildId = Number(payload.guildId);
          const rawCh = (payload.channel as Record<string, unknown>) ?? {};
          const channelId = Number(rawCh.id);
          if (!Number.isSafeInteger(guildId) || guildId <= 0 || !Number.isSafeInteger(channelId) || channelId <= 0 || !String(rawCh.name ?? '').trim()) break;
          const newCh: Channel = {
            id: channelId,
            guildId,
            name: String(rawCh.name),
            type: ((rawCh.type as ChannelType) ?? 'text') === 'voice' ? 'voice' : 'text',
            topic: rawCh.topic ? String(rawCh.topic) : undefined,
            position: Number(rawCh.position ?? 99),
            unreadCount: 0,
          };
          setGuilds((prev) =>
            prev.map((g) =>
              g.id === guildId && !g.channels.some((c) => c.id === newCh.id)
                ? { ...g, channels: [...g.channels, newCh] }
                : g,
            ),
          );
          break;
        }
        case 'CHANNEL_UPDATED': {
          const rawCh = (payload.channel as Record<string, unknown>) ?? payload;
          const channelId = Number(rawCh.id ?? payload.channelId);
          const channelType = String(rawCh.channelType ?? rawCh.type ?? '').toLowerCase();
          setGuilds((prev) => prev.map((guild) => ({
            ...guild,
            channels: guild.channels.map((channel) => channel.id === channelId
              ? {
                  ...channel,
                  name: String(rawCh.name ?? channel.name),
                  topic: rawCh.topic === undefined ? channel.topic : rawCh.topic == null ? undefined : String(rawCh.topic),
                  ...(channelType ? { type: channelType.includes('voice') ? 'voice' as const : 'text' as const } : {}),
                }
              : channel),
          })));
          break;
        }
        case 'CHANNEL_DELETED': {
          const channelId = Number(payload.channelId ?? (payload.channel as Record<string, unknown> | undefined)?.id);
          const guildId = Number(payload.guildId ?? (payload.channel as Record<string, unknown> | undefined)?.guildId);
          const targetGuild = guilds.find((guild) => guild.id === guildId || guild.channels.some((channel) => channel.id === channelId));
          if (!targetGuild) break;
          const remaining = targetGuild.channels.filter((channel) => channel.id !== channelId);
          setGuilds((prev) => prev.map((guild) => guild.id === targetGuild.id
            ? { ...guild, channels: guild.channels.filter((channel) => channel.id !== channelId) }
            : guild));
          setChannelMembersById((prev) => {
            return Object.fromEntries(Object.entries(prev).filter(([id]) => Number(id) !== channelId));
          });
          if (activeVoiceChannelId === channelId) {
            if (connectionMode === 'live') wsClient.send('VOICE_LEAVE', { channelId });
            setActiveVoiceChannelId(null);
            setVoiceParticipants([]);
          }
          if (activeGuildId === targetGuild.id && activeChannelId === channelId) {
            const nextChannel = remaining.find((channel) => channel.type === 'text') ?? remaining[0];
            setActiveChannelId(nextChannel?.id ?? 0);
            setReplyingTo(null);
            setEditingMessage(null);
          }
          break;
        }
        case 'VOICE_PARTICIPANTS': {
          const rawList = Array.isArray(payload.participants) ? payload.participants : [];
          const channelId = Number(payload.channelId ?? activeVoiceChannelId);
          if (!Number.isSafeInteger(channelId) || channelId <= 0) break;
          const participants = rawList.filter((p: Record<string, unknown>) =>
            Number.isSafeInteger(Number(p.userId)) && Number(p.userId) > 0 && String(p.username ?? '').trim(),
          );
          setVoiceParticipants(
            participants.map((p: Record<string, unknown>) => ({
              userId: Number(p.userId),
              username: String(p.username),
              displayName: String(p.displayName ?? p.username ?? 'User'),
              avatarColor: pickAvatarColor(Number(p.userId)),
              channelId: Number(p.channelId ?? channelId),
              muted: Boolean(p.muted),
              deafened: Boolean(p.deafened),
              cameraOn: Boolean(p.cameraOn),
              speaking: Boolean(p.speaking),
            })),
          );
          break;
        }
        case 'SIGNAL_OFFER':
        case 'SIGNAL_ANSWER':
        case 'SIGNAL_ICE': {
          const fromUserId = Number(payload.fromUserId);
          const channelId = Number(payload.channelId ?? payload.roomId);
          if (!Number.isSafeInteger(fromUserId) || fromUserId <= 0 || !Number.isSafeInteger(channelId) || channelId <= 0) break;
          setVoiceSignals((prev) => [...prev.slice(-49), {
            id: `${type}-${Date.now()}-${fromUserId}`,
            type,
            payload,
          }]);
          break;
        }
        case 'PONG_RTT': {
          const seq = Number(payload.seq ?? rttSeqRef.current);
          const timestampMs = Date.now();
          const clientTs = Number(payload.clientTimestampMs);
          if (!Number.isFinite(clientTs) || clientTs <= 0 || clientTs > timestampMs) break;
          const wsRtt = timestampMs - clientTs;
          const udpValue = payload.udpRttMs == null ? null : Number(payload.udpRttMs);
          setRttSamples((prev) => [
            ...prev.slice(-29),
            {
              seq,
              timestampMs,
              wsRttMs: wsRtt,
              tcpRttMs: wsRtt,
              udpRttMs: udpValue !== null && Number.isFinite(udpValue) && udpValue >= 0 ? udpValue : null,
              udpFallback: false,
            },
          ]);
          break;
        }
        default:
          break;
      }
    });

    const refreshAfterReconnect = async () => {
      if (!currentUser) return;
      const reconnectGeneration = sessionGenerationRef.current;
      const reconnectUserId = currentUser.id;
      const sessionIsCurrent = () => sessionGenerationRef.current === reconnectGeneration
        && sessionUserIdRef.current === reconnectUserId;
      const [guildsResult, friendsResult, dmsResult] = await Promise.allSettled([
        apiClient.getGuilds(),
        apiClient.getFriends(currentUser.id),
        apiClient.getDms(),
      ]);
      if (!sessionIsCurrent()) return;

      if (guildsResult.status === 'fulfilled') setGuilds(guildsResult.value);
      if (friendsResult.status === 'fulfilled') {
        setFriends(friendsResult.value.friends);
        setFriendRequests(friendsResult.value.requests);
      }
      if (dmsResult.status === 'fulfilled') setDmConversations(dmsResult.value);

      const selectedView = activeViewRef.current;
      const selectedChannelId = activeChannelRef.current;
      const selectedDmId = activeDmRef.current;
      if (selectedView === 'guild' && selectedChannelId > 0) {
        const [messagesResult, membersResult] = await Promise.allSettled([
          apiClient.getChannelMessages(selectedChannelId),
          apiClient.getChannelMembers(selectedChannelId),
        ]);
        if (!sessionIsCurrent()) return;
        if (messagesResult.status === 'fulfilled') {
          setMessagesByChannel((prev) => ({
            ...prev,
            [selectedChannelId]: mergeMessageHistory(prev[selectedChannelId] ?? [], messagesResult.value),
          }));
          if (activeViewRef.current === 'guild' && activeChannelRef.current === selectedChannelId
              && document.visibilityState === 'visible') {
            const latestId = messagesResult.value.at(-1)?.id;
            if (latestId) void apiClient.markChannelRead(selectedChannelId, latestId).then((readState) => {
              if (activeViewRef.current !== 'guild' || activeChannelRef.current !== selectedChannelId
                  || document.visibilityState !== 'visible') return;
              setGuilds((prev) => prev.map((guild) => ({
                ...guild,
                channels: guild.channels.map((channel) => channel.id === selectedChannelId
                  ? { ...channel, unreadCount: readState.unreadCount ?? 0 }
                  : channel),
              })));
            }).catch(() => undefined);
          }
        } else if (messagesResult.reason instanceof Error && /403|forbidden/i.test(messagesResult.reason.message)) {
          setMessagesByChannel((prev) => ({ ...prev, [selectedChannelId]: [] }));
          setChannelMembersById((prev) => ({ ...prev, [selectedChannelId]: null }));
        }
        if (membersResult.status === 'fulfilled') {
          setChannelMembersById((prev) => ({ ...prev, [selectedChannelId]: membersResult.value }));
          if (activeViewRef.current === 'guild' && activeChannelRef.current === selectedChannelId) {
            setMembers(membersResult.value);
          }
        }
      } else if (selectedView === 'dm' && selectedDmId) {
        const messagesResult = await Promise.allSettled([apiClient.getDmMessages(selectedDmId)]);
        if (!sessionIsCurrent()) return;
        if (messagesResult[0].status === 'fulfilled') {
          const refreshed = messagesResult[0].value;
          setDmMessagesByConv((prev) => ({
            ...prev,
            [selectedDmId]: mergeMessageHistory(prev[selectedDmId] ?? [], refreshed),
          }));
          if (activeViewRef.current === 'dm' && activeDmRef.current === selectedDmId
              && document.visibilityState === 'visible') {
            const latestId = refreshed.at(-1)?.id;
            if (latestId) {
              void apiClient.markDmRead(selectedDmId, latestId).then((readState) => {
                if (activeViewRef.current !== 'dm' || activeDmRef.current !== selectedDmId
                    || document.visibilityState !== 'visible') return;
                setDmConversations((prev) => prev.map((conversation) => conversation.id === selectedDmId
                  ? { ...conversation, unreadCount: readState.unreadCount }
                  : conversation));
              }).catch(() => undefined);
            }
          }
        }
      }
    };

    const unsubState = wsClient.onConnectionState((connected) => {
      if (connectionMode === 'live') {
        setIsConnected(connected);
      }
      if (connected) {
        if (hasConnectedOnceRef.current) void refreshAfterReconnect();
        hasConnectedOnceRef.current = true;
      }
    });

    return () => {
      unsubEvent();
      unsubState();
    };
  }, [activeChannelId, activeDmId, activeGuildId, activeVoiceChannelId, addToast, connectionMode, currentUser, guilds, viewMode]);

  // Derived active guild, channel, messages, pinnedMessages
  const activeGuild = useMemo(
    () => guilds.find((g) => g.id === activeGuildId) ?? guilds[0] ?? null,
    [guilds, activeGuildId],
  );

  const activeChannel = useMemo(() => {
    if (!activeGuild) return null;
    return (
      activeGuild.channels.find((c) => c.id === activeChannelId) ??
      activeGuild.channels[0] ??
      null
    );
  }, [activeGuild, activeChannelId]);

  const messages = useMemo(
    () => messagesByChannel[activeChannelId] ?? [],
    [messagesByChannel, activeChannelId],
  );

  const pinnedMessages = useMemo(
    () => messages.filter((m) => m.pinned && !m.deletedAt),
    [messages],
  );

  const dmMessages = useMemo(
    () => (activeDmId ? dmMessagesByConv[activeDmId] ?? [] : []),
    [dmMessagesByConv, activeDmId],
  );

  useEffect(() => {
    const markVisibleConversationRead = () => {
      if (document.visibilityState !== 'visible') return;
      if (viewMode === 'guild' && activeChannelId > 0) {
        const latestId = messages.at(-1)?.id;
        if (!latestId) return;
        const selectedChannelId = activeChannelId;
        void apiClient.markChannelRead(selectedChannelId, latestId).then((readState) => {
          if (activeViewRef.current !== 'guild' || activeChannelRef.current !== selectedChannelId
              || document.visibilityState !== 'visible') return;
          setGuilds((prev) => prev.map((guild) => ({
            ...guild,
            channels: guild.channels.map((channel) => channel.id === selectedChannelId
              ? { ...channel, unreadCount: readState.unreadCount ?? 0 }
              : channel),
          })));
        }).catch(() => undefined);
      } else if (viewMode === 'dm' && activeDmId) {
        const latestId = dmMessages.at(-1)?.id;
        if (!latestId) return;
        void apiClient.markDmRead(activeDmId, latestId).then((readState) => {
          setDmConversations((prev) => prev.map((conversation) => conversation.id === activeDmId
            ? { ...conversation, unreadCount: readState.unreadCount }
            : conversation));
        }).catch(() => undefined);
      }
    };
    document.addEventListener('visibilitychange', markVisibleConversationRead);
    window.addEventListener('focus', markVisibleConversationRead);
    markVisibleConversationRead();
    return () => {
      document.removeEventListener('visibilitychange', markVisibleConversationRead);
      window.removeEventListener('focus', markVisibleConversationRead);
    };
  }, [activeChannelId, activeDmId, dmMessages, messages, viewMode]);

  // Actions implementation
  const hydrateFromBackend = useCallback(
    async (authToken: string, user: User): Promise<boolean> => {
      sessionGenerationRef.current += 1;
      channelLoadRequestIdRef.current += 1;
      searchRequestIdRef.current += 1;
      sessionUserIdRef.current = user.id;
      activeViewRef.current = 'guild';
      activeChannelRef.current = 0;
      activeDmRef.current = null;
      const hydrateGeneration = sessionGenerationRef.current;
      const isCurrentSession = () => hydrateGeneration === sessionGenerationRef.current
        && sessionUserIdRef.current === user.id;
      // Login/register already hydrate the session directly. Mark restoration as
      // handled before setting state so the restore effect cannot fetch it twice.
      sessionRestoreStartedRef.current = true;
      try {
        localStorage.setItem('oceanic_token', authToken);
        localStorage.setItem('oceanic_user', JSON.stringify(user));
      } catch {
        // Storage may be unavailable in private browsing; keep the session in memory.
      }
      apiClient.setToken(authToken);
      setToken(authToken);
      setCurrentUser(user);
      setGuilds([]);
      setActiveGuildId(0);
      setActiveChannelId(0);
      setMessagesByChannel({});
      setMembers([]);
      setTypingUsers([]);
      setReplyingTo(null);
      setEditingMessage(null);
      setSearchResults([]);
      setSearchQuery('');
      setFriends([]);
      setFriendRequests([]);
      setDmConversations([]);
      setActiveDmId(null);
      setDmMessagesByConv({});
      setVoiceParticipants([]);
      setVoiceSignals([]);
      setActiveVoiceChannelId(null);
      setLocalVoiceState({ muted: false, deafened: false, cameraOn: false, screenSharing: false });
      setRttSamples([]);
      setFrameLogs([]);
      setViewMode('guild');
      setChannelMembersById({});
      setMonitorOpen(false);
      setSettingsOpen(false);
      setCommandPaletteOpen(false);
      setMemberSidebarOpen(false);
      setMobileDrawerOpen('none');
      setToasts([]);
      setIsConnected(false);
      hasConnectedOnceRef.current = false;
      wsClient.connect(authToken);

      try {
        const remoteGuilds = await apiClient.getGuilds();
        if (!isCurrentSession()) return false;
        setGuilds(remoteGuilds);
        if (remoteGuilds.length > 0) {
          const firstGuild = remoteGuilds[0];
          setActiveGuildId(firstGuild.id);
          const firstChannel = firstGuild.channels.find((channel) => channel.type === 'text') ?? firstGuild.channels[0];
          if (firstChannel) {
            activeViewRef.current = 'guild';
            activeChannelRef.current = firstChannel.id;
            activeDmRef.current = null;
            setActiveChannelId(firstChannel.id);
            const [msgs, chMembers] = await Promise.all([
              apiClient.getChannelMessages(firstChannel.id),
              apiClient.getChannelMembers(firstChannel.id),
            ]);
            if (!isCurrentSession()) return false;
            setMessagesByChannel((prev) => ({ ...prev, [firstChannel.id]: msgs }));
            setChannelMembersById((prev) => ({ ...prev, [firstChannel.id]: chMembers }));
            setMembers(chMembers);
          }
        }
        const [{ friends: remoteFriends, requests }, remoteDms] = await Promise.all([
          apiClient.getFriends(user.id),
          apiClient.getDms(),
        ]);
        if (!isCurrentSession()) return false;
        setFriends(remoteFriends);
        setFriendRequests(requests);
        setDmConversations(remoteDms);
        return true;
      } catch (error) {
        if (!isCurrentSession()) return false;
        addToast('warning', 'Một số dữ liệu chưa tải được', userFacingError(error, { locale, action: 'load data', fallback: 'Hãy kiểm tra kết nối rồi thử tải lại.', fallbackEn: 'Some data could not be loaded. Check your connection and try again.' }));
        return true;
      }
    },
    [addToast, locale],
  );

  useEffect(() => {
    if (sessionRestoreStartedRef.current) return;
    sessionRestoreStartedRef.current = true;
    if (!token || !currentUser) return;
    const restoreGeneration = sessionGenerationRef.current;
    apiClient.setToken(token);
    void apiClient.getMe()
      .then((verifiedUser) => {
        if (restoreGeneration !== sessionGenerationRef.current) return;
        return hydrateFromBackend(token, verifiedUser);
      })
      .catch((error: unknown) => {
        if (restoreGeneration !== sessionGenerationRef.current) return;
        const message = error instanceof Error ? error.message.toLowerCase() : '';
        if (message.includes('token') || message.includes('auth')) {
          try {
            localStorage.removeItem('oceanic_token');
            localStorage.removeItem('oceanic_user');
          } catch {
            // Storage may be unavailable in private browsing.
          }
          apiClient.setToken(null);
          sessionGenerationRef.current += 1;
          sessionUserIdRef.current = null;
          setCurrentUser(null);
          setToken(null);
          return;
        }
        addToast('warning', 'Chưa thể khôi phục phiên', 'Hãy kiểm tra kết nối rồi tải lại ứng dụng.');
      });
  }, [addToast, currentUser, hydrateFromBackend, locale, token]);

  const login = useCallback(
    async (username: string, password: string) => {
      try {
        const authGeneration = sessionGenerationRef.current;
        const res = await apiClient.login(username, password);
        if (authGeneration !== sessionGenerationRef.current) return;
        if (!(await hydrateFromBackend(res.token, res.user))) return;
        addToast('success', 'Đăng nhập thành công', `Xin chào ${res.user.displayName}!`);
      } catch (error) {
        addToast('error', 'Không thể đăng nhập', userFacingError(error, { locale, action: 'sign in' }));
        throw error;
      }
    },
    [addToast, hydrateFromBackend, locale],
  );

  const register = useCallback(
    async (username: string, password: string, displayName: string) => {
      try {
        const authGeneration = sessionGenerationRef.current;
        const res = await apiClient.register(username, password, displayName);
        if (authGeneration !== sessionGenerationRef.current) return;
        if (!(await hydrateFromBackend(res.token, res.user))) return;
        addToast('success', 'Đăng ký tài khoản thành công', `Chào mừng ${res.user.displayName}!`);
      } catch (error) {
        addToast('error', 'Không thể tạo tài khoản', userFacingError(error, { locale, action: 'create account' }));
        throw error;
      }
    },
    [addToast, hydrateFromBackend, locale],
  );

  const logout = useCallback(() => {
    sessionGenerationRef.current += 1;
    channelLoadRequestIdRef.current += 1;
    searchRequestIdRef.current += 1;
    sessionUserIdRef.current = null;
    activeViewRef.current = 'guild';
    activeChannelRef.current = 0;
    activeDmRef.current = null;
    try {
      localStorage.removeItem('oceanic_token');
      localStorage.removeItem('oceanic_user');
    } catch {
      // Storage may be unavailable in private browsing.
    }
    wsClient.disconnect();
    apiClient.setToken(null);
    setCurrentUser(null);
    setToken(null);
    setIsConnected(false);
    setGuilds([]);
    setViewMode('guild');
    setActiveGuildId(0);
    setActiveChannelId(0);
    setMessagesByChannel({});
    setMembers([]);
    setTypingUsers([]);
    setReplyingTo(null);
    setEditingMessage(null);
    setSearchResults([]);
    setSearchQuery('');
    setFriends([]);
    setFriendRequests([]);
    setDmConversations([]);
    setActiveDmId(null);
    setDmMessagesByConv({});
    setVoiceParticipants([]);
    setVoiceSignals([]);
    setActiveVoiceChannelId(null);
    setLocalVoiceState({ muted: false, deafened: false, cameraOn: false, screenSharing: false });
    setRttSamples([]);
    setFrameLogs([]);
    hasConnectedOnceRef.current = false;
    setChannelMembersById({});
    setMonitorOpen(false);
    setSettingsOpen(false);
    setCommandPaletteOpen(false);
    setMemberSidebarOpen(false);
    setMobileDrawerOpen('none');
    setToasts([]);
    addToast('info', 'Đã đăng xuất khỏi không gian làm việc');
  }, [addToast]);

  const setTheme = useCallback((nextTheme: 'light' | 'dark') => {
    setThemeState(nextTheme);
  }, []);

  const toggleTheme = useCallback(() => {
    setThemeState((prev) => (prev === 'light' ? 'dark' : 'light'));
  }, []);

  const setLocale = useCallback((nextLocale: 'vi' | 'en') => {
    setLocaleState(nextLocale);
  }, []);

  const loadSelectedChannel = useCallback(async (channelId: number) => {
    if (channelId <= 0) return;
    const requestId = ++channelLoadRequestIdRef.current;
    const sessionGeneration = sessionGenerationRef.current;
    const userId = sessionUserIdRef.current;
    const isCurrentRequest = () => requestId === channelLoadRequestIdRef.current
      && sessionGeneration === sessionGenerationRef.current
      && userId === sessionUserIdRef.current;

    const [messagesResult, membersResult] = await Promise.allSettled([
      apiClient.getChannelMessages(channelId),
      apiClient.getChannelMembers(channelId),
    ]);
    if (!isCurrentRequest()) return;

    if (messagesResult.status === 'fulfilled') {
      setMessagesByChannel((prev) => ({
        ...prev,
        [channelId]: mergeMessageHistory(prev[channelId] ?? [], messagesResult.value),
      }));
    } else if (messagesResult.reason instanceof Error && /403|forbidden/i.test(messagesResult.reason.message)) {
      setMessagesByChannel((prev) => ({ ...prev, [channelId]: [] }));
    }

    if (membersResult.status === 'fulfilled') {
      setChannelMembersById((prev) => ({ ...prev, [channelId]: membersResult.value }));
      if (activeViewRef.current === 'guild' && activeChannelRef.current === channelId) {
        setMembers(membersResult.value);
      }
    } else if (membersResult.reason instanceof Error && /403|forbidden/i.test(membersResult.reason.message)) {
      setChannelMembersById((prev) => ({ ...prev, [channelId]: null }));
      if (activeViewRef.current === 'guild' && activeChannelRef.current === channelId) {
        setMembers([]);
      }
    }

    if ((messagesResult.status === 'rejected' || membersResult.status === 'rejected')
        && activeViewRef.current === 'guild' && activeChannelRef.current === channelId) {
      addToast('warning', 'Một số dữ liệu chưa tải được', 'Vui lòng kiểm tra kết nối rồi thử lại.');
    }
  }, [addToast]);

  const selectGuild = useCallback(
    async (guildId: number) => {
      const targetGuild = guilds.find((guild) => guild.id === guildId);
      if (!targetGuild) return;
      const firstChannel = targetGuild.channels.find((channel) => channel.type === 'text')
        ?? targetGuild.channels[0];

      activeViewRef.current = 'guild';
      activeChannelRef.current = firstChannel?.id ?? 0;
      setViewMode('guild');
      setActiveGuildId(guildId);
      setReplyingTo(null);
      setEditingMessage(null);
      setActiveChannelId(firstChannel?.id ?? 0);
      setMembers([]);

      if (firstChannel) await loadSelectedChannel(firstChannel.id);
    },
    [guilds, loadSelectedChannel],
  );

  const selectChannel = useCallback(
    async (channelId: number) => {
      activeViewRef.current = 'guild';
      activeChannelRef.current = channelId;
      setViewMode('guild');
      setActiveChannelId(channelId);
      setReplyingTo(null);
      setEditingMessage(null);
      setMembers(channelMembersById[channelId] ?? []);
      if (connectionMode === 'live') await loadSelectedChannel(channelId);
    },
    [channelMembersById, connectionMode, loadSelectedChannel],
  );

  const loadChannelMembers = useCallback(
    async (channelId: number) => {
      if (Object.prototype.hasOwnProperty.call(channelMembersById, channelId) || channelMembersLoadingRef.current.has(channelId)) return;
      channelMembersLoadingRef.current.add(channelId);
      try {
        const channelMembers = await apiClient.getChannelMembers(channelId);
        setChannelMembersById((prev) => ({ ...prev, [channelId]: channelMembers }));
      } catch {
        setChannelMembersById((prev) => ({ ...prev, [channelId]: null }));
      } finally {
        channelMembersLoadingRef.current.delete(channelId);
      }
    },
    [channelMembersById],
  );

  const createGuild = useCallback(
    async (name: string, iconText?: string) => {
      const trimmed = name.trim();
      if (!trimmed) return;
      const created = await apiClient.createGuild(trimmed, iconText);
      setGuilds((prev) => [...prev, created]);
      const firstChannel = created.channels.find((channel) => channel.type === 'text') ?? created.channels[0];
      activeViewRef.current = 'guild';
      activeChannelRef.current = firstChannel?.id ?? 0;
      setViewMode('guild');
      setActiveGuildId(created.id);
      setActiveChannelId(firstChannel?.id ?? 0);
      setMembers([]);
      if (firstChannel) await loadSelectedChannel(firstChannel.id);
      addToast('success', 'Đã tạo máy chủ mới', created.name);
    },
    [addToast, loadSelectedChannel],
  );

  const createChannel = useCallback(
    async (guildId: number, name: string, type: ChannelType, topic?: string) => {
      const cleanName =
        type === 'text'
          ? name.trim().toLowerCase().replace(/\s+/g, '-')
          : name.trim();
      if (!cleanName) return;

      const channel = await apiClient.createChannel(guildId, cleanName, type, topic);
      setGuilds((prev) => prev.map((guild) => guild.id === guildId
        ? { ...guild, channels: [...guild.channels, channel] }
        : guild));
      if (type === 'text') {
        activeViewRef.current = 'guild';
        activeChannelRef.current = channel.id;
        setViewMode('guild');
        setActiveGuildId(guildId);
        setActiveChannelId(channel.id);
        setMembers([]);
        await loadSelectedChannel(channel.id);
      }
      addToast('success', 'Đã tạo kênh mới', `#${channel.name}`);
    },
    [addToast, loadSelectedChannel],
  );

  const updateChannel = useCallback(
    async (channelId: number, name: string, topic: string) => {
      const cleanName = name.trim();
      if (!cleanName) throw new Error('Channel name is required.');
      const existingChannel = guilds.flatMap((guild) => guild.channels).find((channel) => channel.id === channelId);
      if (!existingChannel) throw new Error('Channel not found.');

      if (connectionMode === 'live') {
        const updated = await apiClient.updateChannel(channelId, cleanName, topic.trim());
        setGuilds((prev) => prev.map((guild) => ({
          ...guild,
          channels: guild.channels.map((channel) => channel.id === channelId
            ? { ...channel, name: updated.name, topic: updated.topic, type: updated.type }
            : channel),
        })));
      } else {
        setGuilds((prev) => prev.map((guild) => ({
          ...guild,
          channels: guild.channels.map((channel) => channel.id === channelId
            ? { ...channel, name: cleanName, topic: topic.trim() || undefined }
            : channel),
        })));
      }
      addToast('success', 'Đã cập nhật kênh', `#${cleanName}`);
    },
    [addToast, connectionMode, guilds],
  );

  const deleteChannel = useCallback(
    async (channelId: number) => {
      const guild = guilds.find((item) => item.channels.some((channel) => channel.id === channelId));
      if (!guild) throw new Error('Channel not found.');

      if (connectionMode === 'live') {
        await apiClient.deleteChannel(channelId);
      }

      const remaining = guild.channels.filter((channel) => channel.id !== channelId);
      setGuilds((prev) => prev.map((item) => item.id === guild.id
        ? { ...item, channels: item.channels.filter((channel) => channel.id !== channelId) }
        : item));
      setChannelMembersById((prev) => {
        return Object.fromEntries(Object.entries(prev).filter(([id]) => Number(id) !== channelId));
      });
      if (activeVoiceChannelId === channelId) {
        if (connectionMode === 'live') wsClient.send('VOICE_LEAVE', { channelId });
        setActiveVoiceChannelId(null);
        setVoiceParticipants([]);
      }
      if (activeGuildId === guild.id && activeChannelId === channelId) {
        const nextChannel = remaining.find((channel) => channel.type === 'text') ?? remaining[0];
        activeViewRef.current = 'guild';
        activeChannelRef.current = nextChannel?.id ?? 0;
        setActiveChannelId(nextChannel?.id ?? 0);
        setMembers([]);
        setReplyingTo(null);
        setEditingMessage(null);
        if (nextChannel) await loadSelectedChannel(nextChannel.id);
      }
      addToast('success', 'Đã xóa kênh', guild.channels.find((channel) => channel.id === channelId)?.name);
    },
    [activeChannelId, activeGuildId, activeVoiceChannelId, addToast, connectionMode, guilds, loadSelectedChannel],
  );

  const createInvite = useCallback(
    async (guildId: number): Promise<string> => {
      const code = await apiClient.createInvite(guildId);
      addToast('success', 'Đã tạo mã mời máy chủ', `Mã mời: ${code}`);
      return code;
    },
    [addToast],
  );

  const joinGuildByInvite = useCallback(
    async (code: string) => {
      const clean = code.trim().toUpperCase();
      if (!clean) return;
      const joined = await apiClient.joinGuildByInvite(clean);
      setGuilds((prev) => prev.some((guild) => guild.id === joined.id) ? prev : [...prev, joined]);
      const firstChannel = joined.channels.find((channel) => channel.type === 'text') ?? joined.channels[0];
      activeViewRef.current = 'guild';
      activeChannelRef.current = firstChannel?.id ?? 0;
      setViewMode('guild');
      setActiveGuildId(joined.id);
      setActiveChannelId(firstChannel?.id ?? 0);
      setMembers([]);
      if (firstChannel) await loadSelectedChannel(firstChannel.id);
      addToast('success', 'Đã tham gia máy chủ', joined.name);
    },
    [addToast, loadSelectedChannel],
  );

  const sendMessage = useCallback(
    async (content: string, attachments: Attachment[] = []) => {
      const trimmed = content.trim();
      if (!trimmed && attachments.length === 0) return;
      if (!currentUser || activeChannelId <= 0) throw new Error('Choose a channel before sending a message.');
      await wsClient.request('SEND_MESSAGE', {
        channelId: activeChannelId,
        content: trimmed,
        replyToId: replyingTo?.id ?? null,
        attachmentIds: attachments.map((attachment) => attachment.id),
      });
      setReplyingTo(null);
    },
    [activeChannelId, currentUser, replyingTo],
  );

  const editMessage = useCallback(
    async (messageId: number, content: string) => {
      const trimmed = content.trim();
      if (!trimmed) return;
      await wsClient.request('EDIT_MESSAGE', { messageId, content: trimmed });
      setEditingMessage(null);
    },
    [],
  );

  const deleteMessage = useCallback(
    async (messageId: number) => {
      try {
        await wsClient.request('DELETE_MESSAGE', { messageId });
      } catch (error) {
        addToast('error', 'Tin nhắn chưa được xóa', userFacingError(error, { locale, action: 'delete message' }));
      }
    },
    [addToast, locale],
  );

  const toggleReaction = useCallback(async (messageId: number, emoji: string) => {
    const conversationId = viewMode === 'guild' ? undefined : activeDmId ?? undefined;
    try {
      if (!currentUser) throw new Error('Sign in before reacting to a message.');
      await wsClient.request('TOGGLE_REACTION', {
        messageId,
        emoji,
        ...(conversationId ? { conversationId } : {}),
      });
    } catch (error) {
      addToast('error', 'Chưa thể thả cảm xúc', userFacingError(error, { locale, action: 'react to message' }));
    }
  }, [activeDmId, addToast, currentUser, locale, viewMode]);

  const togglePinMessage = useCallback(async (messageId: number, pinned: boolean) => {
    try {
      await wsClient.request('PIN_MESSAGE', { messageId, pinned });
    } catch (error) {
      addToast('error', 'Chưa thể ghim tin nhắn', userFacingError(error, { locale, action: 'pin message' }));
    }
  }, [addToast, locale]);

  const searchChannelMessages = useCallback(
    async (query: string) => {
      const requestId = ++searchRequestIdRef.current;
      setSearchQuery(query);
      if (!query.trim()) {
        setSearchResults([]);
        return;
      }
      setSearchResults([]);
      try {
        const results = await apiClient.searchMessages(query, activeGuildId, activeChannelId);
        if (requestId === searchRequestIdRef.current) setSearchResults(results);
      } catch {
        if (requestId === searchRequestIdRef.current) setSearchResults([]);
        if (requestId === searchRequestIdRef.current) {
          addToast('error', 'Chưa thể tìm tin nhắn', 'Vui lòng thử lại.');
        }
      }
    },
    [activeChannelId, activeGuildId, addToast],
  );

  const uploadFile = useCallback(
    async (file: File): Promise<Attachment> => {
      const channelId = viewMode === 'guild' ? activeChannelId : undefined;
      const dmId = viewMode !== 'guild' ? activeDmId ?? undefined : undefined;
      if (!channelId && !dmId) throw new Error('Choose a conversation before uploading a file.');
      const uploaded = await apiClient.uploadFile(file, channelId || undefined, dmId);
      addToast('success', 'Đã tải tệp lên', file.name);
      return uploaded;
    },
    [activeChannelId, activeDmId, addToast, viewMode],
  );

  const notifyTyping = useCallback(
    (isTyping: boolean) => {
      if (!activeChannelId) return;
      wsClient.send(isTyping ? 'TYPING_START' : 'TYPING_STOP', { channelId: activeChannelId });
    },
    [activeChannelId],
  );

  const updatePresence = useCallback(
    async (status: PresenceStatus, customStatus?: string) => {
      if (!currentUser) return;
      const updatedUser: User = {
        ...currentUser,
        status,
        customStatus: customStatus !== undefined ? customStatus : currentUser.customStatus,
        online: status !== 'OFFLINE',
      };
      try {
        await wsClient.request('PRESENCE_UPDATE', {
          status,
          customStatus: updatedUser.customStatus ?? null,
        });
      } catch (error) {
        addToast('error', 'Chưa cập nhật trạng thái', userFacingError(error, { locale, action: 'change status' }));
        return;
      }
      setCurrentUser(updatedUser);
      setMembers((prev) => prev.map((member) => member.id === updatedUser.id ? updatedUser : member));
      const statusLabels: Record<PresenceStatus, string> = locale === 'vi'
        ? { ONLINE: 'Có mặt', IDLE: 'Tạm vắng', DND: 'Bận', OFFLINE: 'Ẩn trạng thái' }
        : { ONLINE: 'Available', IDLE: 'Away', DND: 'Busy', OFFLINE: 'Invisible' };
      addToast('success', 'Đã cập nhật trạng thái', `${statusLabels[status]}${updatedUser.customStatus ? ` • ${updatedUser.customStatus}` : ''}`);
    },
    [addToast, currentUser, locale],
  );

  const updateMemberRole = useCallback(
    async (userId: number, role: MemberRole) => {
      await apiClient.updateMemberRole(activeChannelId, userId, role);
      setMembers((prev) => prev.map((member) => {
        if (member.id === userId) return { ...member, role };
        if (role === 'OWNER' && member.role === 'OWNER') return { ...member, role: 'ADMIN' as const };
        return member;
      }));
      setChannelMembersById((prev) => {
        const channelMembers = prev[activeChannelId];
        if (!channelMembers) return prev;
        return {
          ...prev,
          [activeChannelId]: channelMembers.map((member) => {
            if (member.id === userId) return { ...member, role };
            if (role === 'OWNER' && member.role === 'OWNER') return { ...member, role: 'ADMIN' as const };
            return member;
          }),
        };
      });
      appendFrameLog({
        direction: 'OUT',
        transport: 'HTTP',
        rawFrame: `PATCH /api/channels/${activeChannelId}/members/${userId}/role {"role":"${role}"}`,
        summary: `Updated Member #${userId} Role -> ${role}`,
      });
      addToast('success', 'Đã cập nhật phân quyền thành viên', `Vai trò mới: ${role}`);
    },
    [activeChannelId, addToast, appendFrameLog],
  );

  const sendFriendRequest = useCallback(
    async (usernameOrId: string) => {
      const clean = usernameOrId.trim();
      if (!clean) return;
      await apiClient.sendFriendRequest(clean);
      addToast('success', 'Đã gửi lời mời kết bạn', `Tới: ${clean}`);
    },
    [addToast],
  );

  const respondFriendRequest = useCallback(
    async (requestId: number, accept: boolean) => {
      await apiClient.respondFriendRequest(requestId, accept);
      const target = friendRequests.find((r) => r.id === requestId);
      const { friends: updatedFriends, requests: updatedRequests } = await apiClient.getFriends(currentUser!.id);
      setFriends(updatedFriends);
      setFriendRequests(updatedRequests);
      addToast(accept ? 'success' : 'info', accept ? 'Đã chấp nhận lời mời kết bạn' : 'Đã từ chối lời mời kết bạn', target?.user.displayName);
    },
    [addToast, currentUser, friendRequests],
  );

  const loadAndMarkDmRead = useCallback(async (dmId: number) => {
    const readGeneration = sessionGenerationRef.current;
    const readUserId = sessionUserIdRef.current;
    try {
      const messages = await apiClient.getDmMessages(dmId);
      if (readGeneration !== sessionGenerationRef.current || readUserId !== sessionUserIdRef.current) return;
      setDmMessagesByConv((prev) => ({
        ...prev,
        [dmId]: mergeMessageHistory(prev[dmId] ?? [], messages),
      }));
      if (activeViewRef.current !== 'dm' || activeDmRef.current !== dmId
          || document.visibilityState !== 'visible') return;
      const lastReadMessageId = messages.at(-1)?.id ?? 0;
      if (lastReadMessageId <= 0) return;
      const readState = await apiClient.markDmRead(dmId, lastReadMessageId);
      if (readGeneration !== sessionGenerationRef.current || readUserId !== sessionUserIdRef.current
          || activeViewRef.current !== 'dm' || activeDmRef.current !== dmId
          || document.visibilityState !== 'visible') return;
      setDmConversations((prev) => prev.map((conversation) =>
        conversation.id === dmId ? { ...conversation, unreadCount: readState.unreadCount } : conversation,
      ));
    } catch {
      addToast('warning', 'Chưa thể đồng bộ tin nhắn đã đọc', 'Hãy kiểm tra kết nối rồi thử lại.');
    }
  }, [addToast]);

  const openDmWithUser = useCallback(
    async (userId: number) => {
      const existing = dmConversations.find((c) => c.peerUser.id === userId);
      if (existing) {
        activeViewRef.current = 'dm';
        activeDmRef.current = existing.id;
        setReplyingTo(null);
        setEditingMessage(null);
        setActiveDmId(existing.id);
        setViewMode('dm');
        await loadAndMarkDmRead(existing.id);
        return;
      }
      const conversation = await apiClient.openDm(userId);
      activeViewRef.current = 'dm';
      activeDmRef.current = conversation.id;
      setReplyingTo(null);
      setEditingMessage(null);
      setDmConversations((prev) => prev.some((item) => item.id === conversation.id) ? prev : [conversation, ...prev]);
      setActiveDmId(conversation.id);
      setViewMode('dm');
      await loadAndMarkDmRead(conversation.id);
    },
    [dmConversations, loadAndMarkDmRead],
  );

  const selectDm = useCallback(
    async (dmId: number) => {
      activeViewRef.current = 'dm';
      activeDmRef.current = dmId;
      setReplyingTo(null);
      setEditingMessage(null);
      setActiveDmId(dmId);
      setViewMode('dm');
      await loadAndMarkDmRead(dmId);
    },
    [loadAndMarkDmRead],
  );

  const sendDmMessage = useCallback(
    async (content: string, attachments: Attachment[] = []) => {
      const trimmed = content.trim();
      if ((!trimmed && attachments.length === 0) || !activeDmId) return;
      const conv = dmConversations.find((c) => c.id === activeDmId);
      if (!conv) throw new Error('Direct conversation not found.');
      await wsClient.request('SEND_DM', {
        conversationId: activeDmId,
        peerUserId: conv.peerUser.id,
        content: trimmed,
        attachmentIds: attachments.map((attachment) => attachment.id),
      });
    },
    [activeDmId, dmConversations],
  );

  const joinVoiceChannel = useCallback(
    async (channelId: number) => {
      if (!currentUser) return;
      try {
        await wsClient.request('VOICE_JOIN', {
          channelId,
          muted: localVoiceState.muted,
          cameraOn: localVoiceState.cameraOn,
        });
      } catch (error) {
        addToast('error', 'Chưa vào được phòng thoại', userFacingError(error, { locale, action: 'join voice channel' }));
        return;
      }
      setActiveVoiceChannelId(channelId);
      addToast('success', 'Đã vào phòng thoại');
    },
    [addToast, currentUser, localVoiceState, locale],
  );

  const leaveVoiceChannel = useCallback(() => {
    if (!activeVoiceChannelId) return;
    const prevChannelId = activeVoiceChannelId;
    if (!wsClient.send('VOICE_LEAVE', { channelId: prevChannelId })) {
      addToast('error', 'Chưa thể rời phòng thoại', 'Hãy thử lại sau.');
      return;
    }
    setActiveVoiceChannelId(null);
    addToast('info', 'Đã rời phòng thoại');
  }, [activeVoiceChannelId, addToast]);

  const updateLocalVoiceState = useCallback(
    async (
      partial: Partial<{
        muted: boolean;
        deafened: boolean;
        cameraOn: boolean;
        screenSharing: boolean;
      }>,
    ) => {
      const next = { ...localVoiceState, ...partial };
      const changesServerState = 'muted' in partial || 'deafened' in partial || 'cameraOn' in partial;
      if (activeVoiceChannelId && currentUser && changesServerState) {
        try {
          await wsClient.request('VOICE_STATE', {
            channelId: activeVoiceChannelId,
            muted: next.muted,
            cameraOn: next.cameraOn,
          });
        } catch (error) {
          addToast('error', 'Chưa cập nhật được cài đặt âm thanh', userFacingError(error, { locale, action: 'change voice settings' }));
          return;
        }
      }
      setLocalVoiceState(next);
    },
    [activeVoiceChannelId, addToast, currentUser, localVoiceState, locale],
  );

  const sendVoiceSignal = useCallback(
    (
      type: 'SIGNAL_OFFER' | 'SIGNAL_ANSWER' | 'SIGNAL_ICE',
      targetUserId: number,
      data: Record<string, unknown>,
    ) => {
      const channelId = activeVoiceChannelId ?? activeChannelId;
      if (!channelId || !wsClient.isOpen()) return false;
      return wsClient.send(type, { channelId, targetUserId, ...data });
    },
    [activeChannelId, activeVoiceChannelId],
  );

  const consumeVoiceSignal = useCallback((id: string) => {
    setVoiceSignals((prev) => prev.filter((signal) => signal.id !== id));
  }, []);

  const triggerRttProbe = useCallback(async () => {
    const seq = rttSeqRef.current++;
    const startMs = Date.now();
    if (connectionMode !== 'live') throw new Error('Tính năng kiểm tra kết nối chưa sẵn sàng.');

    if (wsClient.isOpen()) {
      wsClient.send('PING_RTT', { seq, clientTimestampMs: startMs, transport: 'WS' });
    }

    try {
      const probe = await apiClient.probeUdpRtt(seq, startMs);
      if (probe.udpRttMs === null || !probe.rawDatagramReceived) {
        addToast('warning', 'Chưa kiểm tra được tốc độ kết nối', 'Hãy thử lại sau.');
        return;
      }
      appendFrameLog({
        direction: 'OUT',
        transport: 'UDP',
        rawFrame: probe.rawDatagramSent,
        summary: `UDP Probe TX seq=${seq}`,
      });
      appendFrameLog({
        direction: 'IN',
        transport: 'UDP',
        rawFrame: `${probe.rawDatagramReceived} (RTT=${probe.udpRttMs}ms)`,
        summary: `UDP Probe RX seq=${seq} (${probe.udpRttMs} ms)`,
      });
    } catch (error) {
      addToast('error', 'Không kiểm tra được tốc độ kết nối', 'Hãy thử lại sau.');
      throw error;
    }
  }, [addToast, appendFrameLog, connectionMode]);

  const clearFrameLogs = useCallback(() => {
    setFrameLogs([]);
  }, []);

  const value = useMemo<AppStoreValue>(
    () => ({
      currentUser,
      token,
      isAuthenticated: Boolean(currentUser && token),
      connectionMode,
      isConnected,
      theme,
      locale,
      viewMode,
      guilds,
      channelMembersById,
      activeGuildId,
      activeChannelId,
      activeGuild,
      activeChannel,
      messages,
      pinnedMessages,
      searchResults,
      searchQuery,
      members,
      typingUsers,
      replyingTo,
      editingMessage,
      friends,
      friendRequests,
      dmConversations,
      activeDmId,
      dmMessages,
      activeVoiceChannelId,
      voiceParticipants,
      voiceSignals,
      localVoiceState,
      rttSamples,
      frameLogs,
      isMonitorOpen,
      isSettingsOpen,
      isCommandPaletteOpen,
      isMemberSidebarOpen,
      mobileDrawerOpen,
      toasts,
      login,
      register,
      logout,
      setTheme,
      toggleTheme,
      setLocale,
      setViewMode,
      selectGuild,
      selectChannel,
      createGuild,
      createChannel,
      loadChannelMembers,
      updateChannel,
      deleteChannel,
      createInvite,
      joinGuildByInvite,
      sendMessage,
      editMessage,
      deleteMessage,
      toggleReaction,
      togglePinMessage,
      setReplyingTo,
      setEditingMessage,
      searchChannelMessages,
      uploadFile,
      notifyTyping,
      updatePresence,
      updateMemberRole,
      sendFriendRequest,
      respondFriendRequest,
      openDmWithUser,
      selectDm,
      sendDmMessage,
      joinVoiceChannel,
      leaveVoiceChannel,
      updateLocalVoiceState,
      sendVoiceSignal,
      consumeVoiceSignal,
      triggerRttProbe,
      clearFrameLogs,
      setMonitorOpen,
      setSettingsOpen,
      setCommandPaletteOpen,
      setMemberSidebarOpen,
      setMobileDrawerOpen,
      addToast,
      dismissToast,
    }),
    [
      currentUser,
      token,
      connectionMode,
      isConnected,
      theme,
      locale,
      viewMode,
      guilds,
      channelMembersById,
      activeGuildId,
      activeChannelId,
      activeGuild,
      activeChannel,
      messages,
      pinnedMessages,
      searchResults,
      searchQuery,
      members,
      typingUsers,
      replyingTo,
      editingMessage,
      friends,
      friendRequests,
      dmConversations,
      activeDmId,
      dmMessages,
      activeVoiceChannelId,
      voiceParticipants,
      voiceSignals,
      localVoiceState,
      rttSamples,
      frameLogs,
      isMonitorOpen,
      isSettingsOpen,
      isCommandPaletteOpen,
      isMemberSidebarOpen,
      mobileDrawerOpen,
      toasts,
      login,
      register,
      logout,
      setTheme,
      toggleTheme,
      setLocale,
      selectGuild,
      selectChannel,
      createGuild,
      createChannel,
      loadChannelMembers,
      updateChannel,
      deleteChannel,
      createInvite,
      joinGuildByInvite,
      sendMessage,
      editMessage,
      deleteMessage,
      toggleReaction,
      togglePinMessage,
      searchChannelMessages,
      uploadFile,
      notifyTyping,
      updatePresence,
      updateMemberRole,
      sendFriendRequest,
      respondFriendRequest,
      openDmWithUser,
      selectDm,
      sendDmMessage,
      joinVoiceChannel,
      leaveVoiceChannel,
      updateLocalVoiceState,
      sendVoiceSignal,
      consumeVoiceSignal,
      triggerRttProbe,
      clearFrameLogs,
      addToast,
      dismissToast,
    ],
  );

  return <AppStoreContext.Provider value={value}>{children}</AppStoreContext.Provider>;
};

export function useAppStore(): AppStoreValue {
  const ctx = useContext(AppStoreContext);
  if (!ctx) {
    throw new Error('useAppStore() must be used inside <AppStoreProvider>');
  }
  return ctx;
}
