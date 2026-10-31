import React, { useState, useRef, useEffect } from 'react';
import {
  Hash,
  Pin,
  Search,
  Users,
  Download,
  X,
  MessageSquare,
} from 'lucide-react';
import { useAppStore } from '../../shared/state/appStore';
import { userFacingError } from '../../shared/ui/userFacingError';
import type { Message, Attachment } from '../../shared/types/api';
import { Avatar } from '../../shared/ui/Avatar';
import { MessageComposer } from './MessageComposer';
import { MessageDateDivider } from './MessageDateDivider';
import { PinnedMessageBar } from './PinnedMessageBar';
import { MessageItem, SafeMarkdownContent } from './MessageItem';
import { VoiceWaveformPlayer } from './VoiceWaveformPlayer';

export { SafeMarkdownContent };

export const ChatStage: React.FC = () => {
  const {
    currentUser,
    locale,
    addToast,
    activeChannel,
    messages,
    pinnedMessages,
    searchResults,
    searchQuery,
    members,
    channelMembersById,
    editingMessage,
    setReplyingTo,
    setEditingMessage,
    editMessage,
    deleteMessage,
    toggleReaction,
    togglePinMessage,
    searchChannelMessages,
    isMemberSidebarOpen,
    setMemberSidebarOpen,
  } = useAppStore();

  const [localSearchInput, setLocalSearchInput] = useState(searchQuery || '');
  const [isSearchPopoverOpen, setIsSearchPopoverOpen] = useState(false);
  const [isPinsPopoverOpen, setIsPinsPopoverOpen] = useState(false);
  const [inlineEditText, setInlineEditText] = useState('');
  const [highlightedMsgId, setHighlightedMsgId] = useState<number | null>(null);
  const [previewImage, setPreviewImage] = useState<{
    url: string;
    filename: string;
  } | null>(null);

  const streamEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    streamEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages?.length, activeChannel?.id]);

  const channelSpecificMembers = activeChannel
    ? channelMembersById?.[activeChannel.id]
    : null;
  const effectiveMembers = Array.isArray(channelSpecificMembers) && channelSpecificMembers.length > 0
    ? channelSpecificMembers
    : Array.isArray(members) ? members : [];

  const currentChannelRole = Array.isArray(channelSpecificMembers)
    ? channelSpecificMembers.find((member) => member.id === currentUser?.id)?.role
    : undefined;
  const canModerate = currentChannelRole === 'OWNER' || currentChannelRole === 'ADMIN';

  const channelMessages: Message[] = Array.isArray(messages) ? messages : [];
  const effectivePinned: Message[] =
    Array.isArray(pinnedMessages) && pinnedMessages.length > 0
      ? pinnedMessages
      : channelMessages.filter((m) => m.pinned && !m.deletedAt);

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

  const handleSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const q = localSearchInput.trim();
    void searchChannelMessages(q);
    setIsSearchPopoverOpen(Boolean(q));
  };

  const formatTime = (iso: string) => {
    try {
      const d = new Date(iso);
      return d.toLocaleTimeString('vi-VN', {
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

  const handleSaveInlineEdit = async (msgId: number) => {
    const trimmed = inlineEditText.trim();
    if (trimmed) {
      try {
        await editMessage(msgId, trimmed);
      } catch (error) {
        addToast('error', 'Tin nhắn chưa được sửa', userFacingError(error, { locale, action: 'edit message' }));
        return;
      }
    }
    setEditingMessage(null);
  };

  const isVoiceAttachment = (att: Attachment): boolean =>
    Boolean(
      att.contentType?.startsWith('audio/') ||
        /\.(webm|ogg|mp3|wav|m4a)$/i.test(att.filename)
    );

  if (!activeChannel) {
    return (
      <section
        aria-label="Khu vực trò chuyện chính"
        className="flex h-full min-w-0 flex-1 items-center justify-center bg-slate-50/50 px-6 text-center text-slate-600 dark:bg-[#090d16] dark:text-slate-300"
      >
        <div className="max-w-sm">
          <MessageSquare className="mx-auto mb-4 h-10 w-10 text-blue-500" aria-hidden="true" />
          <h2 className="text-lg font-bold text-slate-900 dark:text-white">Chưa có kênh trò chuyện</h2>
          <p className="mt-2 text-sm leading-6">
            Tạo một máy chủ mới hoặc tham gia bằng mã mời từ các nút bên trái để bắt đầu trò chuyện.
          </p>
        </div>
      </section>
    );
  }

  return (
    <section
      aria-label="Khu vực trò chuyện chính"
      className="relative flex h-full min-w-0 flex-1 flex-col bg-slate-50/50 dark:bg-[#090d16] text-slate-900 dark:text-slate-100 transition-colors duration-200"
    >
      {/* STICKY 60px STAGE HEADER (Cliq Royal Blue & Slate Daylight/Night) */}
      <header className="sticky top-0 z-20 flex h-[60px] shrink-0 items-center justify-between border-b border-slate-200/80 dark:border-slate-800/80 bg-white/90 dark:bg-[#0f172a]/90 px-5 backdrop-blur-md transition-colors">
        {/* Channel avatar and name */}
        <div className="flex min-w-0 flex-1 items-center gap-3 pr-3">
          <Avatar
            name={activeChannel.name}
            size="md"
            status="ONLINE"
            showStatus
            color="#2563eb"
          />

          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <h2 className="truncate text-[15px] font-bold tracking-tight text-slate-900 dark:text-slate-100">
                #{activeChannel.name}
              </h2>

            </div>

            <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400 font-medium">
              <span className="flex items-center gap-1.5">
                <span className="h-1.5 w-1.5 rounded-full bg-green-500" />
                <span>Có mặt</span>
              </span>
              {activeChannel?.topic && (
                <>
                  <span>•</span>
                  <span className="truncate max-w-[320px]">{activeChannel.topic}</span>
                </>
              )}
            </div>
          </div>
        </div>

        {/* Right: Search, Pinned Messages Drawer Trigger, Member Sidebar Toggle */}
        <div className="flex shrink-0 items-center gap-2">
          {/* Search Input */}
          <div className="relative hidden md:block">
            <form onSubmit={handleSearchSubmit} className="relative">
              <Search className="pointer-events-none absolute top-1/2 left-2.5 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
              <input
                type="text"
                value={localSearchInput}
                onChange={(e) => {
                  const val = e.target.value;
                  setLocalSearchInput(val);
                  void searchChannelMessages(val);
                  setIsSearchPopoverOpen(Boolean(val.trim()));
                }}
                onFocus={() => {
                  if (localSearchInput.trim()) setIsSearchPopoverOpen(true);
                }}
                placeholder="Tìm tin nhắn..."
                className="h-8 w-44 rounded-xl border border-slate-200/90 dark:border-slate-700/80 bg-slate-50/90 dark:bg-slate-800/80 pr-7 pl-8 text-xs font-medium text-slate-900 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500 transition-all outline-none focus:w-60 focus:border-[#2563eb] focus:bg-white dark:focus:bg-slate-800"
              />
              {localSearchInput && (
                <button
                  type="button"
                  onClick={() => {
                    setLocalSearchInput('');
                    void searchChannelMessages('');
                    setIsSearchPopoverOpen(false);
                  }}
                  className="absolute top-1/2 right-2 -translate-y-1/2 text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 cursor-pointer"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              )}
            </form>

            {/* Search Results Popover */}
            {isSearchPopoverOpen && (
              <div className="absolute top-10 right-0 z-50 max-h-96 w-80 overflow-y-auto rounded-2xl border border-slate-200/90 dark:border-slate-700 bg-white dark:bg-slate-900 text-slate-900 dark:text-slate-100 p-3 shadow-2xl cliq-scrollbar">
                <div className="mb-2 flex items-center justify-between border-b border-slate-200/80 dark:border-slate-800 pb-1.5">
                  <span className="text-xs font-bold">
                    Kết quả tìm kiếm ({searchResults?.length || 0})
                  </span>
                  <button
                    type="button"
                    onClick={() => setIsSearchPopoverOpen(false)}
                    className="rounded p-0.5 text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 cursor-pointer"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>

                {Array.isArray(searchResults) && searchResults.length > 0 ? (
                  <div className="space-y-1.5">
                    {searchResults.map((resMsg) => (
                      <button
                        key={resMsg.id}
                        type="button"
                        onClick={() => {
                          scrollToMessage(resMsg.id);
                          setIsSearchPopoverOpen(false);
                        }}
                        className="w-full rounded-xl border border-slate-200/80 dark:border-slate-800 bg-slate-50/80 dark:bg-slate-800/70 hover:border-[#2563eb] dark:hover:border-blue-500 p-2 text-left text-xs transition cursor-pointer"
                      >
                        <div className="flex items-center justify-between font-bold text-[#2563eb] dark:text-blue-400">
                          <span>{resMsg.senderName}</span>
                          <span className="font-mono text-[10px] text-slate-400">
                            {formatTime(resMsg.createdAt)}
                          </span>
                        </div>
                        <p className="mt-1 line-clamp-2 text-slate-700 dark:text-slate-300">
                          {resMsg.content}
                        </p>
                      </button>
                    ))}
                  </div>
                ) : (
                  <div className="py-6 text-center text-xs text-slate-400">
                    Không tìm thấy tin nhắn khớp với &ldquo;{localSearchInput}&rdquo;
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Pinned Messages Popover Trigger */}
          <div className="relative">
            <button
              type="button"
              onClick={() => setIsPinsPopoverOpen(!isPinsPopoverOpen)}
              aria-label="Tin nhắn đã ghim"
              title="Tin nhắn đã ghim"
              className={`relative flex h-8 items-center gap-1 rounded-xl border px-2.5 text-xs font-bold transition cursor-pointer ${
                isPinsPopoverOpen
                  ? 'border-[#2563eb] bg-blue-50 dark:bg-blue-950/60 text-[#2563eb] dark:text-blue-400'
                  : 'border-slate-200/90 dark:border-slate-700/80 bg-slate-50/90 dark:bg-slate-800/80 text-slate-600 dark:text-slate-300 hover:border-[#2563eb] hover:text-[#2563eb]'
              }`}
            >
              <Pin className="h-3.5 w-3.5 text-[#2563eb] dark:text-blue-400" />
              <span className="tabular-nums">{effectivePinned.length}</span>
            </button>

            {isPinsPopoverOpen && (
              <div className="absolute top-10 right-0 z-50 max-h-96 w-80 overflow-y-auto rounded-2xl border border-slate-200/90 dark:border-slate-700 bg-white dark:bg-slate-900 text-slate-900 dark:text-slate-100 p-3 shadow-2xl cliq-scrollbar">
                <div className="mb-2 flex items-center justify-between border-b border-slate-200/80 dark:border-slate-800 pb-2">
                  <span className="flex items-center gap-1.5 text-xs font-bold">
                    <Pin className="h-3.5 w-3.5 text-[#2563eb] dark:text-blue-400" />
                    <span>Tin nhắn đã ghim ({effectivePinned.length})</span>
                  </span>
                  <button
                    type="button"
                    onClick={() => setIsPinsPopoverOpen(false)}
                    className="rounded p-0.5 text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 cursor-pointer"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>

                {effectivePinned.length > 0 ? (
                  <div className="space-y-2">
                    {effectivePinned.map((pinMsg) => (
                      <div
                        key={pinMsg.id}
                        className="rounded-xl border border-slate-200/80 dark:border-slate-800 bg-slate-50/80 dark:bg-slate-800/70 p-2.5 text-xs"
                      >
                        <div className="flex items-center justify-between">
                          <span className="font-bold text-[#2563eb] dark:text-blue-400">
                            {pinMsg.senderName}
                          </span>
                          <div className="flex items-center gap-1">
                            <button
                              type="button"
                              onClick={() => {
                                scrollToMessage(pinMsg.id);
                                setIsPinsPopoverOpen(false);
                              }}
                              className="rounded-md px-1.5 py-0.5 text-[10px] font-bold text-[#2563eb] dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/60 cursor-pointer"
                            >
                              Đi tới
                            </button>
                            <button
                              type="button"
                              onClick={() =>
                                void togglePinMessage(pinMsg.id, false)
                              }
                              title="Bỏ ghim"
                              className="rounded p-0.5 text-slate-400 hover:text-rose-500 cursor-pointer"
                            >
                              <X className="h-3 w-3" />
                            </button>
                          </div>
                        </div>
                        <p className="mt-1 line-clamp-3 text-slate-700 dark:text-slate-300">
                          {pinMsg.content}
                        </p>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="py-6 text-center text-xs text-slate-400">
                    Chưa có tin nhắn nào được ghim trong kênh này.
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Member Sidebar Toggle */}
          <button
            type="button"
            onClick={() => setMemberSidebarOpen(!isMemberSidebarOpen)}
            aria-label="Ẩn/Hiện danh sách thành viên"
            title="Ẩn/Hiện danh sách thành viên"
            className={`flex h-8 w-8 items-center justify-center rounded-xl border transition cursor-pointer ${
              isMemberSidebarOpen
                ? 'border-[#2563eb] bg-blue-50 text-[#2563eb] dark:bg-blue-950/60 dark:text-blue-400'
                : 'border-slate-200/90 dark:border-slate-700/80 bg-slate-50/90 dark:bg-slate-800/80 text-slate-600 dark:text-slate-300 hover:border-[#2563eb] hover:text-[#2563eb]'
            }`}
          >
            <Users className="h-4 w-4" />
          </button>
        </div>
      </header>

      {/* PINNED MESSAGE BAR (Cliq Top Banner right below header when pins exist) */}
      {effectivePinned.length > 0 && (
        <PinnedMessageBar
          pinnedMessages={effectivePinned}
          onJumpToMessage={scrollToMessage}
          onUnpin={(id) => void togglePinMessage(id, false)}
          canUnpin={true}
          pinnedLabel="Đã ghim"
          unpinTitle="Bỏ ghim tin nhắn"
        />
      )}

      {/* MESSAGE STREAM */}
      <div className="flex-1 overflow-y-auto px-5 pt-4 pb-2 cliq-scrollbar">
        {/* Channel Welcome Hero Banner in Cliq Royal Blue */}
        <div className="mb-5 rounded-2xl border border-slate-200/90 dark:border-slate-800 bg-white/90 dark:bg-slate-900/80 p-4 shadow-2xs backdrop-blur-xs">
          <div className="flex items-center gap-3.5">
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-[#2563eb] text-white shadow-xs">
              <Hash className="h-5 w-5" />
            </div>
            <div className="min-w-0">
              <h3 className="text-base font-bold text-slate-900 dark:text-slate-100">
                Chào mừng đến với #{activeChannel?.name || 'general'}!
              </h3>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                {activeChannel?.topic ||
                  'Kênh để mọi người cùng trò chuyện.'}
              </p>
            </div>
          </div>
        </div>

        {/* Empty State */}
        {channelMessages.length === 0 ? (
          <div className="my-12 flex flex-col items-center justify-center text-center px-4">
            <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-2xl bg-blue-50 dark:bg-blue-950/60 text-[#2563eb] dark:text-blue-400 border border-blue-200/70 dark:border-blue-800/60">
              <MessageSquare className="h-6 w-6" />
            </div>
            <p className="text-sm font-bold text-slate-700 dark:text-slate-200">
              Chưa có tin nhắn nào trong #{activeChannel?.name || 'general'}
            </p>
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400 max-w-sm">
              Hãy gửi tin nhắn đầu tiên để bắt đầu cuộc trò chuyện.
            </p>
          </div>
        ) : (
          /* Messages List using M2 MessageDateDivider & MessageItem */
          <div className="space-y-0.5">
            {channelMessages.map((msg, idx) => {
              const prevMsg = idx > 0 ? channelMessages[idx - 1] : undefined;
              const nextMsg =
                idx < channelMessages.length - 1
                  ? channelMessages[idx + 1]
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

              const senderMember = effectiveMembers.find(
                (m) => m.id === msg.senderId
              );
              const senderStatus = senderMember?.status;
              const senderRoleLabel =
                senderMember?.role === 'OWNER'
                  ? 'Chủ nhóm'
                  : senderMember?.role === 'ADMIN'
                  ? 'Quản trị viên'
                  : null;

              const mentionsMe =
                Boolean(
                  currentUser?.username &&
                    msg.content
                      ?.toLowerCase()
                      .includes(`@${currentUser.username.toLowerCase()}`)
                ) || Boolean(msg.content?.toLowerCase().includes('@all'));

              return (
                <React.Fragment key={msg.id}>
                  {showDateDivider && (
                    <MessageDateDivider label={formatDateDivider(msg.createdAt)} />
                  )}

                  <MessageItem
                    message={msg}
                    isOwn={isOwn}
                    isCompact={isCompact}
                    isNextSameSender={isNextSameSender}
                    senderStatus={senderStatus}
                    senderRoleLabel={senderRoleLabel}
                    currentUserId={currentUser?.id}
                    currentUsername={currentUser?.username}
                    isHighlighted={highlightedMsgId === msg.id || mentionsMe}
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
                      void togglePinMessage(messageId, !msg.pinned)
                    }
                    onToggleReaction={(messageId, emoji) =>
                      void toggleReaction(messageId, emoji)
                    }
                    onJumpToMessage={scrollToMessage}
                    onOpenImagePreview={(url, filename) =>
                      setPreviewImage({ url, filename })
                    }
                    canPin={true}
                    canDelete={isOwn || canModerate}
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
            <div ref={streamEndRef} />
          </div>
        )}
      </div>

      {/* MESSAGE COMPOSER */}
      <MessageComposer key={`channel-${activeChannel?.id ?? 0}`} mode="channel" />

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
              <span className="truncate font-bold">{previewImage.filename}</span>
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
};

export default ChatStage;
