import React, { useState, useRef, useEffect } from 'react';
import { motion } from 'framer-motion';
import {
  Users,
  UserPlus,
  UserMinus,
  Ban,
  MessageSquare,
  Phone,
  Check,
  X,
  Search,
  Radio,
  FileText,
  Download,
  Sparkles,
  Clock,
  Pin,
  Image as ImageIcon,
  ShieldOff,
} from 'lucide-react';
import { useAppStore } from '../../shared/state/appStore';
import { userFacingError } from '../../shared/ui/userFacingError';
import type {
  User,
  FriendRequest,
  Message,
  Attachment,
  PresenceStatus,
} from '../../shared/types/api';
import { Avatar } from '../../shared/ui/Avatar';
import { MessageComposer } from '../chat/MessageComposer';
import { MessageDateDivider } from '../chat/MessageDateDivider';
import { PinnedMessageBar } from '../chat/PinnedMessageBar';
import { MessageItem } from '../chat/MessageItem';
import { VoiceWaveformPlayer } from '../chat/VoiceWaveformPlayer';

type FriendsTab = 'online' | 'all' | 'pending' | 'blocked' | 'add';

export const FriendsAndDmStage: React.FC = () => {
  const store = useAppStore();
  const {
    currentUser,
    viewMode,
    setViewMode,
    friends,
    friendRequests,
    dmConversations,
    activeDmId,
    dmMessages,
    sendFriendRequest,
    respondFriendRequest,
    openDmWithUser,
    guilds,
    joinVoiceChannel,
    editingMessage,
    setReplyingTo,
    setEditingMessage,
    editMessage,
    deleteMessage,
    toggleReaction,
    togglePinMessage,
    addToast,
  } = store;

  const [activeTab, setActiveTab] = useState<FriendsTab>('online');
  const [filterQuery, setFilterQuery] = useState('');
  const [addUsernameInput, setAddUsernameInput] = useState('');
  const [addStatusMessage, setAddStatusMessage] = useState<{
    type: 'success' | 'error';
    text: string;
  } | null>(null);
  const [isSendingRequest, setIsSendingRequest] = useState(false);

  const blockedUsers: User[] = [];
  const [inlineEditText, setInlineEditText] = useState('');
  const [highlightedMsgId, setHighlightedMsgId] = useState<number | null>(null);
  const [previewImage, setPreviewImage] = useState<{
    url: string;
    filename: string;
  } | null>(null);

  const dmStreamEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (viewMode === 'dm') {
      dmStreamEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [viewMode, dmMessages?.length, activeDmId]);

  const friendsList: User[] = Array.isArray(friends) ? friends : [];
  const pendingRequests: FriendRequest[] = Array.isArray(friendRequests)
    ? friendRequests
    : [];

  const onlineFriends = friendsList.filter(
    (f) => (f.status && f.status !== 'OFFLINE') || f.online
  );

  const filteredFriends = (
    activeTab === 'online' ? onlineFriends : friendsList
  ).filter((f) => {
    if (!filterQuery.trim()) return true;
    const q = filterQuery.toLowerCase();
    return (
      f.username.toLowerCase().includes(q) ||
      Boolean(f.displayName && f.displayName.toLowerCase().includes(q))
    );
  });

  const getPresenceMeta = (status?: PresenceStatus) => {
    switch (status) {
      case 'ONLINE':
        return {
          dot: 'bg-green-500',
          text: 'Có mặt',
          badgeBg:
            'bg-blue-50 dark:bg-blue-950/60 text-[#2563eb] dark:text-blue-400',
        };
      case 'IDLE':
        return {
          dot: 'bg-amber-500',
          text: 'Tạm vắng',
          badgeBg:
            'bg-amber-50 dark:bg-amber-950/60 text-amber-600 dark:text-amber-400',
        };
      case 'DND':
        return {
          dot: 'bg-rose-500',
          text: 'Bận',
          badgeBg:
            'bg-rose-50 dark:bg-rose-950/60 text-rose-600 dark:text-rose-400',
        };
      default:
        return {
          dot: 'bg-slate-400',
          text: 'Ngoại tuyến',
          badgeBg:
            'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400',
        };
    }
  };

  const handleSendFriendRequest = async (e: React.FormEvent) => {
    e.preventDefault();
    const cleanUsername = addUsernameInput.trim().replace(/^@/, '');
    if (!cleanUsername) {
      setAddStatusMessage({
        type: 'error',
        text: 'Vui lòng nhập tên tài khoản (@username) cần kết bạn.',
      });
      return;
    }

    setIsSendingRequest(true);
    setAddStatusMessage(null);
    try {
      await sendFriendRequest(cleanUsername);
      setAddStatusMessage({
        type: 'success',
        text: `Đã gửi lời mời kết bạn tới @${cleanUsername}!`,
      });
      setAddUsernameInput('');
    } catch (err) {
      setAddStatusMessage({
        type: 'error',
        text:
          userFacingError(err, { locale: store.locale, action: 'send friend request', fallback: 'Không thể gửi lời mời kết bạn lúc này. Vui lòng thử lại.', fallbackEn: 'Your friend request could not be sent. Please try again.' }),
      });
    } finally {
      setIsSendingRequest(false);
    }
  };

  const acceptFriendRequest = async (requestId: number) => {
    const ext = store as unknown as {
      acceptFriendRequest?: (id: number) => Promise<void>;
    };
    if (typeof ext.acceptFriendRequest === 'function') {
      await ext.acceptFriendRequest(requestId);
    } else {
      await respondFriendRequest(requestId, true);
    }
  };

  const declineFriendRequest = async (requestId: number) => {
    const ext = store as unknown as {
      declineFriendRequest?: (id: number) => Promise<void>;
    };
    if (typeof ext.declineFriendRequest === 'function') {
      await ext.declineFriendRequest(requestId);
    } else {
      await respondFriendRequest(requestId, false);
    }
  };

  const removeFriend = async (friend: User) => {
    void friend;
    addToast?.('warning', 'Chưa thể xóa bạn bè', 'Tính năng này chưa sẵn sàng.');
  };

  const blockUser = async (target: User) => {
    void target;
    addToast?.('warning', 'Chưa thể chặn người này', 'Tính năng này chưa sẵn sàng.');
  };

  const unblockUser = async (userId: number) => {
    void userId;
    addToast?.('warning', 'Chưa thể bỏ chặn người này', 'Tính năng này chưa sẵn sàng.');
  };

  const handleStartDmWithFriend = async (userId: number) => {
    await openDmWithUser(userId);
    setViewMode('dm');
  };

  const handleQuickVoiceCall = () => {
    const firstGuild =
      Array.isArray(guilds) && guilds.length > 0 ? guilds[0] : null;
    const voiceCh = firstGuild?.channels?.find((c) => c.type === 'voice');
    if (voiceCh) {
      joinVoiceChannel(voiceCh.id);
    } else {
      addToast('warning', 'Chưa có phòng thoại', 'Hãy tạo phòng thoại trong không gian làm việc trước.');
    }
  };

  const formatTime = (iso: string) => {
    try {
      return new Date(iso).toLocaleTimeString('vi-VN', {
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return '';
    }
  };

  const formatDateDivider = (iso: string) => {
    try {
      const d = new Date(iso);
      return d.toLocaleDateString('vi-VN', {
        weekday: 'long',
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
      });
    } catch {
      return 'Hôm nay';
    }
  };

  const isSameDay = (iso1: string, iso2: string) => {
    try {
      const d1 = new Date(iso1);
      const d2 = new Date(iso2);
      return (
        d1.getFullYear() === d2.getFullYear() &&
        d1.getMonth() === d2.getMonth() &&
        d1.getDate() === d2.getDate()
      );
    } catch {
      return true;
    }
  };

  const isGroupedWithPrevious = (curr: Message, prev?: Message) => {
    if (!prev) return false;
    if (curr.senderId !== prev.senderId) return false;
    if (curr.replyTo) return false;
    if (!isSameDay(curr.createdAt, prev.createdAt)) return false;
    try {
      const diffMs =
        new Date(curr.createdAt).getTime() - new Date(prev.createdAt).getTime();
      return diffMs >= 0 && diffMs < 5 * 60 * 1000;
    } catch {
      return false;
    }
  };

  const scrollToMessage = (messageId: number) => {
    setHighlightedMsgId(messageId);
    const el = document.getElementById(`msg-${messageId}`);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    setTimeout(() => {
      setHighlightedMsgId((prev) => (prev === messageId ? null : prev));
    }, 1800);
  };

  const handleSaveInlineEdit = async (msgId: number) => {
    const trimmed = inlineEditText.trim();
    if (trimmed) {
      await editMessage(msgId, trimmed);
    }
    setEditingMessage(null);
  };

  const isVoiceAttachment = (att: Attachment): boolean =>
    Boolean(
      att.contentType?.startsWith('audio/') ||
        /\.(webm|ogg|mp3|wav|m4a)$/i.test(att.filename)
    );

  // ============================================================================
  // MODE 1: 1-ON-1 DIRECT MESSAGE (DM) STAGE (Zoho Cliq Experience)
  // ============================================================================
  if (viewMode === 'dm') {
    const activeConv = Array.isArray(dmConversations)
      ? dmConversations.find((c) => c.id === activeDmId)
      : undefined;
    if (!activeConv) {
      return (
        <section className="flex h-full min-h-0 flex-1 items-center justify-center bg-slate-50/50 px-6 text-slate-900 dark:bg-[#090d16] dark:text-slate-100">
          <div className="max-w-sm text-center">
            <h2 className="text-lg font-bold">Chưa có cuộc trò chuyện nào được chọn</h2>
            <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
              Chọn một người bạn để mở tin nhắn riêng.
            </p>
            <button
              type="button"
              className="btn btn-primary mt-4"
              onClick={() => setViewMode('friends')}
            >
              Mở danh sách bạn bè
            </button>
          </div>
        </section>
      );
    }
    const peer = activeConv?.peerUser;
    const peerName = peer.displayName || peer.username;
    const peerPresence = getPresenceMeta(peer?.status);
    const currentDmMessages: Message[] = Array.isArray(dmMessages)
      ? dmMessages
      : [];

    const effectivePinnedDm = currentDmMessages.filter((m) => m.pinned && !m.deletedAt);

    const handleToggleDmPin = (messageId: number, currentPinned: boolean) => {
      void togglePinMessage(messageId, !currentPinned);
    };

    return (
      <section
        aria-label="Trò chuyện riêng"
        className="relative flex h-full min-w-0 flex-1 flex-col bg-slate-50/50 dark:bg-[#090d16] text-slate-900 dark:text-slate-100 transition-colors duration-200"
      >
        {/* Sticky 60px DM Header in Cliq Royal Blue & Slate */}
        <header className="sticky top-0 z-20 flex h-[60px] shrink-0 items-center justify-between border-b border-slate-200/80 dark:border-slate-800/80 bg-white/90 dark:bg-[#0f172a]/90 px-5 backdrop-blur-md transition-colors">
          <div className="flex min-w-0 items-center gap-3">
            <Avatar
              name={peerName}
              src={peer?.avatarUrl}
              color={peer?.avatarColor || '#2563eb'}
              status={peer?.status || 'ONLINE'}
              showStatus
              size="md"
            />

            <div className="min-w-0">
              <h2 className="truncate text-[15px] font-bold tracking-tight text-slate-900 dark:text-slate-100">
                {peerName}
              </h2>
              <div className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400 font-medium">
                <span className={`h-1.5 w-1.5 rounded-full ${peerPresence.dot}`} />
                <span className="truncate">
                  {peer?.customStatus || peerPresence.text || 'Online'}
                </span>
              </div>
            </div>
          </div>

          {/* Right Action Controls in Cliq Blue & Slate */}
          <div className="flex items-center gap-1.5 text-slate-500 dark:text-slate-400">
            <button
              type="button"
              className="p-2 rounded-xl hover:text-[#2563eb] dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-slate-800 transition cursor-pointer"
              title="Ghim cuộc trò chuyện"
            >
              <Pin className="h-4 w-4" />
            </button>
            <button
              type="button"
              className="p-2 rounded-xl hover:text-[#2563eb] dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-slate-800 transition cursor-pointer"
              title="Ảnh và video"
            >
              <ImageIcon className="h-4 w-4" />
            </button>
            <button
              type="button"
              className="p-2 rounded-xl hover:text-[#2563eb] dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-slate-800 transition cursor-pointer"
              title="Tệp đã chia sẻ"
            >
              <FileText className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={handleQuickVoiceCall}
              title="Gọi thoại"
              className="p-2 rounded-xl text-[#2563eb] dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/50 transition ml-0.5 cursor-pointer"
            >
              <Phone className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={() => setViewMode('friends')}
              className="rounded-xl border border-slate-200/90 dark:border-slate-700/80 bg-slate-50 dark:bg-slate-800 px-3 py-1.5 text-xs font-bold text-slate-700 dark:text-slate-200 hover:border-[#2563eb] hover:text-[#2563eb] dark:hover:text-blue-400 transition ml-1 cursor-pointer"
            >
              Bạn bè
            </button>
          </div>
        </header>

        {/* Pinned Message Bar in DM */}
        {effectivePinnedDm.length > 0 && (
          <PinnedMessageBar
            pinnedMessages={effectivePinnedDm}
            onJumpToMessage={scrollToMessage}
            onUnpin={(id) => handleToggleDmPin(id, true)}
            canUnpin={true}
            pinnedLabel="Đã ghim"
            unpinTitle="Bỏ ghim tin nhắn"
          />
        )}

        {/* 1-on-1 DM Message Stream */}
        <div className="flex-1 overflow-y-auto px-5 pt-4 pb-2 cliq-scrollbar">
          {/* Peer Intro Banner */}
          <div className="mb-5 rounded-2xl border border-slate-200/90 dark:border-slate-800 bg-white/90 dark:bg-slate-900/80 p-4 shadow-2xs">
            <div className="flex items-center gap-3.5">
              <Avatar
                name={peerName}
                src={peer?.avatarUrl}
                color={peer?.avatarColor || '#2563eb'}
                status={peer?.status || 'ONLINE'}
                showStatus
                size="xl"
              />
              <div className="min-w-0">
                <h3 className="text-base font-bold text-slate-900 dark:text-slate-100">
                  {peerName}
                </h3>
                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                  Đây là khởi đầu lịch sử tin nhắn riêng 1-1 giữa bạn và{' '}
                  <strong className="text-[#2563eb] dark:text-blue-400">
                    @{peer?.username || 'peer'}
                  </strong>
                  .
                </p>
              </div>
            </div>
          </div>

          {/* DM Messages rendered via M2 MessageDateDivider & MessageItem */}
          <div className="space-y-0.5">
            {currentDmMessages.map((msg, idx) => {
              const prevMsg =
                idx > 0 ? currentDmMessages[idx - 1] : undefined;
              const nextMsg =
                idx < currentDmMessages.length - 1
                  ? currentDmMessages[idx + 1]
                  : undefined;

              const showDateDivider =
                !prevMsg || !isSameDay(msg.createdAt, prevMsg.createdAt);
              const isCompact =
                !showDateDivider && isGroupedWithPrevious(msg, prevMsg);
              const isNextSameSender = Boolean(
                nextMsg &&
                  isSameDay(msg.createdAt, nextMsg.createdAt) &&
                  isGroupedWithPrevious(nextMsg, msg)
              );

              const isOwn = Boolean(
                currentUser && msg.senderId === currentUser.id
              );
              const isEditing = editingMessage?.id === msg.id;
              const isPinned = Boolean(
                msg.pinned
              );

              return (
                <React.Fragment key={msg.id}>
                  {showDateDivider && (
                    <MessageDateDivider
                      label={formatDateDivider(msg.createdAt)}
                    />
                  )}

                  <MessageItem
                    message={{
                      ...msg,
                      pinned: isPinned,
                      senderAvatarColor:
                        msg.senderAvatarColor ||
                        (isOwn
                          ? currentUser?.avatarColor || '#2563eb'
                          : peer?.avatarColor || '#2563eb'),
                    }}
                    isOwn={isOwn}
                    isCompact={isCompact}
                    isNextSameSender={isNextSameSender}
                    senderStatus={isOwn ? currentUser?.status : peer?.status}
                    currentUserId={currentUser?.id}
                    currentUsername={currentUser?.username}
                    isHighlighted={highlightedMsgId === msg.id}
                    isEditing={isEditing}
                    editingDraft={inlineEditText}
                    onChangeEditingDraft={setInlineEditText}
                    onSaveEdit={() => void handleSaveInlineEdit(msg.id)}
                    onCancelEdit={() => setEditingMessage(null)}
                    onStartEdit={(targetMsg) => {
                      setInlineEditText(targetMsg.content);
                      setEditingMessage(targetMsg);
                    }}
                    onDelete={(messageId) => void deleteMessage(messageId)}
                    onReply={(targetMsg) => setReplyingTo(targetMsg)}
                    onTogglePin={(messageId) =>
                      handleToggleDmPin(messageId, isPinned)
                    }
                    onToggleReaction={(messageId, emoji) =>
                      void toggleReaction(messageId, emoji)
                    }
                    onJumpToMessage={scrollToMessage}
                    onOpenImagePreview={(url, filename) =>
                      setPreviewImage({ url, filename })
                    }
                    canPin={true}
                    canDelete={isOwn}
                    resolveAttachmentUrl={(rawUrl) => {
                      const matched = msg.attachments?.find(
                        (a) => a.url === rawUrl
                      );
                      return matched?.dataUrl || rawUrl;
                    }}
                    isVoiceAttachment={isVoiceAttachment}
                    renderVoiceAttachment={(att, m) => (
                      <VoiceWaveformPlayer
                        src={att.dataUrl || att.url}
                        filename={att.filename}
                        isOutgoing={currentUser?.id === m.senderId}
                        timestamp={formatTime(m.createdAt)}
                      />
                    )}
                    labels={{
                      edited: 'đã sửa',
                      deleted: 'Tin nhắn này đã bị thu hồi.',
                      pinned: 'Đã ghim',
                      reply: 'Trả lời',
                      edit: 'Chỉnh sửa',
                      delete: 'Xóa',
                      pin: 'Ghim tin nhắn',
                      unpin: 'Bỏ ghim',
                      save: 'Lưu',
                      cancel: 'Hủy',
                    }}
                  />
                </React.Fragment>
              );
            })}
            <div ref={dmStreamEndRef} />
          </div>
        </div>

        {/* Shared Message Composer in DM mode */}
        <MessageComposer key={`dm-${activeDmId ?? 0}`} mode="dm" />

        {/* CLIQ IMAGE LIGHTBOX MODAL */}
        {previewImage && (
          <div
            className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-slate-950/85 p-6 backdrop-blur-xs"
            onClick={() => setPreviewImage(null)}
          >
            <div
              onClick={(e) => e.stopPropagation()}
              className="relative max-h-[85vh] max-w-4xl overflow-hidden rounded-2xl border border-slate-700/80 bg-slate-900 p-2.5 shadow-2xl"
            >
              <img
                src={previewImage.url}
                alt={previewImage.filename}
                className="max-h-[75vh] w-auto rounded-xl object-contain mx-auto"
              />
              <div className="mt-2.5 flex items-center justify-between gap-4 px-3 py-1 text-xs text-white">
                <span className="truncate font-bold">
                  {previewImage.filename}
                </span>
                <div className="flex items-center gap-2 shrink-0">
                  <a
                    href={previewImage.url}
                    download={previewImage.filename}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1.5 rounded-xl bg-[#2563eb] hover:bg-[#1d4ed8] px-3 py-1.5 font-bold text-white shadow-xs transition-colors"
                  >
                    <Download className="h-3.5 w-3.5" />
                    <span>Tải xuống</span>
                  </a>
                  <button
                    type="button"
                    onClick={() => setPreviewImage(null)}
                    aria-label="Đóng xem trước ảnh"
                    className="rounded-xl bg-slate-800 hover:bg-slate-700 p-1.5 text-slate-200 transition-colors cursor-pointer"
                  >
                    <X className="h-4 w-4" />
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}
      </section>
    );
  }

  // ============================================================================
  // MODE 2: FRIENDS OVERVIEW & MANAGEMENT STAGE ('friends')
  // ============================================================================
  return (
    <section
      aria-label="Quản lý bạn bè và lời mời kết bạn"
      className="flex h-full min-w-0 flex-1 flex-col bg-slate-50/50 dark:bg-[#090d16] text-slate-900 dark:text-slate-100 transition-colors duration-200"
    >
      {/* TOP FRIENDS NAVIGATION HEADER (60px Cliq Style) */}
      <header className="flex h-[60px] shrink-0 items-center justify-between border-b border-slate-200/80 dark:border-slate-800/80 bg-white/90 dark:bg-[#0f172a]/90 px-5 backdrop-blur-md">
        <div className="flex flex-wrap items-center gap-1.5">
          <div className="mr-2 flex items-center gap-2 border-r border-slate-200 dark:border-slate-700/80 pr-4">
            <Users className="h-5 w-5 text-[#2563eb] dark:text-blue-400" />
            <h2 className="text-[15px] font-bold">Bạn bè</h2>
          </div>

          {/* Tab 1: Online */}
          <button
            type="button"
            onClick={() => setActiveTab('online')}
            className={`relative rounded-xl px-3 py-1.5 text-xs font-bold transition-colors cursor-pointer ${
              activeTab === 'online'
                ? 'text-white'
                : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800'
            }`}
          >
            {activeTab === 'online' && (
              <motion.span
                layoutId="friends-tab-pill"
                className="pointer-events-none absolute inset-0 rounded-xl bg-[#2563eb] shadow-xs"
                transition={{ type: 'spring', stiffness: 420, damping: 30 }}
              />
            )}
            <span className="relative z-10">Trực tuyến ({onlineFriends.length})</span>
          </button>

          {/* Tab 2: All Friends */}
          <button
            type="button"
            onClick={() => setActiveTab('all')}
            className={`relative rounded-xl px-3 py-1.5 text-xs font-bold transition-colors cursor-pointer ${
              activeTab === 'all'
                ? 'text-white'
                : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800'
            }`}
          >
            {activeTab === 'all' && (
              <motion.span
                layoutId="friends-tab-pill"
                className="pointer-events-none absolute inset-0 rounded-xl bg-[#2563eb] shadow-xs"
                transition={{ type: 'spring', stiffness: 420, damping: 30 }}
              />
            )}
            <span className="relative z-10">Tất cả bạn bè ({friendsList.length})</span>
          </button>

          {/* Tab 3: Pending Requests */}
          <button
            type="button"
            onClick={() => setActiveTab('pending')}
            className={`relative flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-bold transition-colors cursor-pointer ${
              activeTab === 'pending'
                ? 'text-white'
                : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800'
            }`}
          >
            {activeTab === 'pending' && (
              <motion.span
                layoutId="friends-tab-pill"
                className="pointer-events-none absolute inset-0 rounded-xl bg-[#2563eb] shadow-xs"
                transition={{ type: 'spring', stiffness: 420, damping: 30 }}
              />
            )}
            <span className="relative z-10">Lời mời kết bạn</span>
            {pendingRequests.length > 0 && (
              <span className="relative z-10 rounded-full bg-rose-500 px-1.5 py-0.2 text-[10px] font-bold text-white tabular-nums">
                {pendingRequests.length}
              </span>
            )}
          </button>

          {/* Tab 4: Blocked */}
          <button
            type="button"
            onClick={() => setActiveTab('blocked')}
            className={`relative flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-xs font-bold transition-colors cursor-pointer ${
              activeTab === 'blocked'
                ? 'text-white'
                : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800'
            }`}
          >
            {activeTab === 'blocked' && (
              <motion.span
                layoutId="friends-tab-pill"
                className="pointer-events-none absolute inset-0 rounded-xl bg-[#2563eb] shadow-xs"
                transition={{ type: 'spring', stiffness: 420, damping: 30 }}
              />
            )}
            <span className="relative z-10">Đã chặn ({blockedUsers.length})</span>
          </button>

          {/* Tab 5: Add Friend (Cliq Royal Blue Accent) */}
          <button
            type="button"
            onClick={() => setActiveTab('add')}
            className={`relative flex items-center gap-1 rounded-xl px-3 py-1.5 text-xs font-bold transition-colors cursor-pointer ${
              activeTab === 'add'
                ? 'text-white'
                : 'bg-blue-50 dark:bg-blue-950/60 text-[#2563eb] dark:text-blue-400 border border-blue-200/80 dark:border-blue-800/60 hover:bg-blue-100/80 dark:hover:bg-blue-900/50'
            }`}
          >
            {activeTab === 'add' && (
              <motion.span
                layoutId="friends-tab-pill"
                className="pointer-events-none absolute inset-0 rounded-xl bg-[#2563eb] shadow-xs"
                transition={{ type: 'spring', stiffness: 420, damping: 30 }}
              />
            )}
            <UserPlus className="relative z-10 h-3.5 w-3.5" />
            <span className="relative z-10">Thêm bạn</span>
          </button>
        </div>
      </header>

      {/* BODY CONTENT BY TAB */}
      <div className="flex-1 overflow-y-auto p-6 cliq-scrollbar">
        <div className="mx-auto max-w-3xl">
          {/* TAB: ADD FRIEND */}
          {activeTab === 'add' && (
            <div className="rounded-2xl border border-slate-200/90 dark:border-slate-800 bg-white dark:bg-slate-900 p-6 shadow-2xs">
              <div className="mb-4 flex items-center gap-3">
                <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-[#2563eb] text-white shadow-xs">
                  <UserPlus className="h-5 w-5" />
                </div>
                <div>
                  <h3 className="text-base font-bold text-slate-900 dark:text-slate-100">
                    Thêm Bạn Bè Bằng Username
                  </h3>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    Nhập tên đăng nhập (@username) của bạn học hoặc thành viên nhóm để kết nối.
                  </p>
                </div>
              </div>

              <form
                onSubmit={(e) => void handleSendFriendRequest(e)}
                className="flex gap-2.5"
              >
                <input
                  type="text"
                  value={addUsernameInput}
                  onChange={(e) => setAddUsernameInput(e.target.value)}
                  placeholder="Nhập username cần tìm..."
                  autoFocus
                  className="flex-1 rounded-xl border border-slate-200/90 dark:border-slate-700 bg-slate-50/90 dark:bg-slate-800 px-4 py-2.5 text-sm font-medium text-slate-900 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500 outline-none focus:border-[#2563eb] focus:bg-white dark:focus:bg-slate-800"
                />
                <button
                  type="submit"
                  disabled={isSendingRequest}
                  className="rounded-xl bg-[#2563eb] hover:bg-[#1d4ed8] px-5 py-2.5 text-xs font-bold text-white shadow-xs transition disabled:opacity-50 cursor-pointer"
                >
                  {isSendingRequest ? 'Đang gửi...' : 'Gửi Lời Mời Kết Bạn'}
                </button>
              </form>

              {addStatusMessage && (
                <div
                  className={`mt-3 rounded-xl px-3.5 py-2.5 text-xs font-bold ${
                    addStatusMessage.type === 'success'
                      ? 'bg-blue-50 dark:bg-blue-950/60 text-[#2563eb] dark:text-blue-400 border border-blue-200/80 dark:border-blue-800/60'
                      : 'bg-rose-50 dark:bg-rose-950/50 text-rose-600 dark:text-rose-400 border border-rose-200/80 dark:border-rose-800/60'
                  }`}
                >
                  {addStatusMessage.text}
                </div>
              )}
            </div>
          )}

          {/* TAB: PENDING FRIEND REQUESTS */}
          {activeTab === 'pending' && (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <h3 className="text-xs font-bold tracking-wider uppercase text-slate-500 dark:text-slate-400">
                  Lời mời kết bạn đang chờ ({pendingRequests.length})
                </h3>
              </div>

              {pendingRequests.length > 0 ? (
                pendingRequests.map((req) => {
                  const isIncoming = req.direction === 'INCOMING';
                  const targetUser = req.user;

                  return (
                    <div
                      key={req.id}
                      className="flex items-center justify-between rounded-2xl border border-slate-200/90 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 shadow-2xs transition"
                    >
                      <div className="flex items-center gap-3">
                        <Avatar
                          name={
                            targetUser?.displayName ||
                            targetUser?.username ||
                            'User'
                          }
                          src={targetUser?.avatarUrl}
                          color={targetUser?.avatarColor || '#2563eb'}
                          status={targetUser?.status}
                          showStatus={Boolean(targetUser?.status)}
                          size="lg"
                        />
                        <div>
                          <div className="text-sm font-bold text-slate-900 dark:text-slate-100">
                            {targetUser?.displayName || targetUser?.username}
                          </div>
                          <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                            <span>@{targetUser?.username}</span>
                            <span>•</span>
                            <span className="inline-flex items-center gap-1 text-[#2563eb] dark:text-blue-400 font-semibold">
                              <Clock className="h-3 w-3" />
                              {isIncoming
                                ? 'Lời mời gửi đến bạn'
                                : 'Đang chờ đối phương đồng ý'}
                            </span>
                          </div>
                        </div>
                      </div>

                      {isIncoming ? (
                        <div className="flex items-center gap-2">
                          <button
                            type="button"
                            onClick={() => void acceptFriendRequest(req.id)}
                            className="flex items-center gap-1 rounded-xl bg-[#2563eb] hover:bg-[#1d4ed8] px-3.5 py-2 text-xs font-bold text-white shadow-xs transition cursor-pointer"
                          >
                            <Check className="h-4 w-4" />
                            <span>Chấp nhận</span>
                          </button>
                          <button
                            type="button"
                            onClick={() => void declineFriendRequest(req.id)}
                            className="flex items-center gap-1 rounded-xl bg-rose-50 dark:bg-rose-950/50 px-3.5 py-2 text-xs font-bold text-rose-600 dark:text-rose-400 hover:bg-rose-500 hover:text-white transition cursor-pointer"
                          >
                            <X className="h-4 w-4" />
                            <span>Từ chối</span>
                          </button>
                        </div>
                      ) : (
                        <button
                          type="button"
                          onClick={() => void declineFriendRequest(req.id)}
                          className="rounded-xl bg-rose-50 dark:bg-rose-950/50 px-3 py-1.5 text-xs font-bold text-rose-600 dark:text-rose-400 hover:bg-rose-500 hover:text-white transition cursor-pointer"
                        >
                          Hủy lời mời
                        </button>
                      )}
                    </div>
                  );
                })
              ) : (
                <div className="rounded-2xl border border-dashed border-slate-300 dark:border-slate-700 p-10 text-center text-slate-500 dark:text-slate-400">
                  <Sparkles className="mx-auto mb-2 h-8 w-8 text-[#2563eb] dark:text-blue-400" />
                  <p className="text-sm font-bold text-slate-700 dark:text-slate-200">
                    Không có lời mời kết bạn nào đang chờ xử lý
                  </p>
                </div>
              )}
            </div>
          )}

          {/* TAB: BLOCKED USERS */}
          {activeTab === 'blocked' && (
            <div className="space-y-3">
              <h3 className="text-xs font-bold tracking-wider uppercase text-slate-500 dark:text-slate-400">
                Danh sách đã chặn ({blockedUsers.length})
              </h3>
              {blockedUsers.length > 0 ? (
                blockedUsers.map((blocked) => (
                  <div
                    key={blocked.id}
                    className="flex items-center justify-between rounded-2xl border border-slate-200/90 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 shadow-2xs"
                  >
                    <div className="flex items-center gap-3">
                      <Avatar
                        name={blocked.displayName || blocked.username}
                        src={blocked.avatarUrl}
                        color={blocked.avatarColor || '#64748b'}
                        size="lg"
                      />
                      <div>
                        <div className="text-sm font-bold text-slate-900 dark:text-slate-100">
                          {blocked.displayName || blocked.username}
                        </div>
                        <div className="text-xs font-mono text-slate-500 dark:text-slate-400">
                          @{blocked.username}
                        </div>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => void unblockUser(blocked.id)}
                      className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 hover:border-[#2563eb] hover:text-[#2563eb] dark:hover:text-blue-400 px-3.5 py-2 text-xs font-bold text-slate-700 dark:text-slate-200 transition cursor-pointer"
                    >
                      <ShieldOff className="h-3.5 w-3.5" />
                      <span>Bỏ chặn</span>
                    </button>
                  </div>
                ))
              ) : (
                <div className="rounded-2xl border border-dashed border-slate-300 dark:border-slate-700 p-10 text-center text-slate-500 dark:text-slate-400">
                  <Ban className="mx-auto mb-2 h-8 w-8 text-[#2563eb] dark:text-blue-400" />
                  <p className="text-sm font-bold text-slate-700 dark:text-slate-200">
                    Bạn chưa chặn người dùng nào
                  </p>
                </div>
              )}
            </div>
          )}

          {/* TAB: ONLINE OR ALL FRIENDS */}
          {(activeTab === 'online' || activeTab === 'all') && (
            <div className="space-y-4">
              {/* Filter Input */}
              <div className="relative">
                <Search className="pointer-events-none absolute top-1/2 left-3.5 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  type="text"
                  value={filterQuery}
                  onChange={(e) => setFilterQuery(e.target.value)}
                  placeholder="Tìm kiếm bạn bè theo tên hoặc @username..."
                  className="w-full rounded-xl border border-slate-200/90 dark:border-slate-700 bg-white dark:bg-slate-900 py-2.5 pr-4 pl-10 text-sm text-slate-900 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500 outline-none transition focus:border-[#2563eb]"
                />
              </div>

              <div className="text-xs font-bold tracking-wider uppercase text-slate-500 dark:text-slate-400">
                {activeTab === 'online'
                  ? `Bạn bè đang trực tuyến — ${filteredFriends.length}`
                  : `Tất cả bạn bè — ${filteredFriends.length}`}
              </div>

              {filteredFriends.length > 0 ? (
                <div className="space-y-2">
                  {filteredFriends.map((friend) => {
                    const pMeta = getPresenceMeta(friend.status);
                    return (
                      <div
                        key={friend.id}
                        className="flex items-center justify-between rounded-2xl border border-slate-200/90 dark:border-slate-800 bg-white dark:bg-slate-900 hover:border-[#2563eb] dark:hover:border-blue-500/60 p-3.5 shadow-2xs transition"
                      >
                        <div className="flex min-w-0 items-center gap-3">
                          <Avatar
                            name={friend.displayName || friend.username || 'F'}
                            src={friend.avatarUrl}
                            color={friend.avatarColor || '#2563eb'}
                            status={friend.status || 'OFFLINE'}
                            showStatus
                            size="lg"
                          />

                          <div className="min-w-0">
                            <div className="flex items-center gap-2">
                              <span className="truncate text-sm font-bold text-slate-900 dark:text-slate-100">
                                {friend.displayName || friend.username}
                              </span>
                              <span className="font-mono text-xs text-slate-500 dark:text-slate-400">
                                @{friend.username}
                              </span>
                            </div>
                            <div className="truncate text-xs text-slate-500 dark:text-slate-400">
                              {friend.customStatus || pMeta.text}
                            </div>
                          </div>
                        </div>

                        <div className="flex items-center gap-1.5">
                          <button
                            type="button"
                            onClick={() =>
                              void handleStartDmWithFriend(friend.id)
                            }
                            className="flex items-center gap-1.5 rounded-xl bg-[#2563eb] hover:bg-[#1d4ed8] px-3.5 py-2 text-xs font-bold text-white shadow-xs transition cursor-pointer"
                          >
                            <MessageSquare className="h-3.5 w-3.5" />
                            <span>Nhắn tin riêng</span>
                          </button>

                          <button
                            type="button"
                            onClick={handleQuickVoiceCall}
                            title="Gọi thoại hoặc video"
                            className="flex h-9 w-9 items-center justify-center rounded-xl border border-slate-200/90 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-[#2563eb] dark:text-blue-400 hover:bg-[#2563eb] hover:text-white hover:border-[#2563eb] transition cursor-pointer"
                          >
                            <Phone className="h-4 w-4" />
                          </button>

                          <button
                            type="button"
                            onClick={() => void removeFriend(friend)}
                            title="Xóa bạn bè"
                            aria-label="Xóa bạn bè"
                            className="flex h-9 w-9 items-center justify-center rounded-xl border border-slate-200/90 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-950/50 dark:hover:text-rose-400 transition cursor-pointer"
                          >
                            <UserMinus className="h-4 w-4" />
                          </button>

                          <button
                            type="button"
                            onClick={() => void blockUser(friend)}
                            title="Chặn người dùng"
                            aria-label="Chặn người dùng"
                            className="flex h-9 w-9 items-center justify-center rounded-xl border border-slate-200/90 dark:border-slate-700 bg-slate-50 dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-600 dark:hover:bg-rose-950/50 dark:hover:text-rose-400 transition cursor-pointer"
                          >
                            <Ban className="h-4 w-4" />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <div className="rounded-2xl border border-dashed border-slate-300 dark:border-slate-700 p-10 text-center text-slate-500 dark:text-slate-400">
                  <Radio className="mx-auto mb-2 h-8 w-8 text-[#2563eb] dark:text-blue-400" />
                  <p className="text-sm font-bold text-slate-700 dark:text-slate-200">
                    {activeTab === 'online'
                      ? 'Hiện chưa có người bạn nào đang trực tuyến'
                      : 'Danh sách bạn bè đang trống'}
                  </p>
                  <button
                    type="button"
                    onClick={() => setActiveTab('add')}
                    className="mt-3 rounded-xl bg-[#2563eb] hover:bg-[#1d4ed8] px-4 py-2 text-xs font-bold text-white shadow-xs transition cursor-pointer"
                  >
                    + Thêm bạn mới
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </section>
  );
};

export default FriendsAndDmStage;
