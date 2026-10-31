import React, { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import {
  Hash,
  Plus,
  UserPlus,
  Copy,
  Check,
  CheckCheck,
  X,
  ChevronDown,
  ChevronRight,
  Pencil,
  Trash2,
  Search,
} from 'lucide-react';
import { useAppStore } from '../../shared/state/appStore';
import { Avatar } from '../../shared/ui/Avatar';
import type {
  Channel,
  DirectConversation,
} from '../../shared/types/api';

export const ChannelSidebar: React.FC = () => {
  const {
    theme,
    viewMode,
    setViewMode,
    activeGuild,
    activeGuildId,
    isConnected,
    currentUser,
    channelMembersById,
    activeChannelId,
    selectChannel,
    createChannel,
    loadChannelMembers,
    updateChannel,
    deleteChannel,
    createInvite,
    typingUsers,
    dmConversations,
    activeDmId,
    selectDm,
  } = useAppStore();

  const isDark = theme === 'dark';

  const activeTab = viewMode === 'guild' ? 'channels' : 'messages';
  const [textCollapsed, setTextCollapsed] = useState(false);
  const [filterQuery, setFilterQuery] = useState('');

  // Invite Modal State
  const [isInviteModalOpen, setIsInviteModalOpen] = useState(false);
  const [generatedInviteCode, setGeneratedInviteCode] = useState<string>('');
  const [inviteError, setInviteError] = useState('');
  const [isGeneratingInvite, setIsGeneratingInvite] = useState(false);
  const [copiedInvite, setCopiedInvite] = useState(false);

  // Create Channel Modal State
  const [isCreateChannelOpen, setIsCreateChannelOpen] = useState(false);
  const [newChannelName, setNewChannelName] = useState('');
  const [newChannelTopic, setNewChannelTopic] = useState('');
  const [isCreatingChannel, setIsCreatingChannel] = useState(false);
  const [editingChannel, setEditingChannel] = useState<Channel | null>(null);
  const [editedName, setEditedName] = useState('');
  const [editedTopic, setEditedTopic] = useState('');
  const [channelSettingsError, setChannelSettingsError] = useState('');
  const [channelSettingsBusy, setChannelSettingsBusy] = useState(false);
  const [confirmChannelDelete, setConfirmChannelDelete] = useState(false);

  useEffect(() => {
    if (!activeGuild) return;
    activeGuild.channels.forEach((channel) => {
      void loadChannelMembers(channel.id);
    });
  }, [activeGuild, loadChannelMembers]);

  const getCurrentMemberRole = (channelId: number) =>
    currentUser
      ? channelMembersById[channelId]?.find((member) => member.id === currentUser.id)?.role
      : undefined;
  const canManageChannel = (channelId: number) => {
    const role = getCurrentMemberRole(channelId);
    return role === 'OWNER' || role === 'ADMIN';
  };
  const canDeleteEditingChannel = editingChannel ? getCurrentMemberRole(editingChannel.id) === 'OWNER' : false;

  const channels: Channel[] = activeGuild?.channels ?? [];
  const normalizedQuery = filterQuery.trim().toLowerCase();

  const textChannels = channels.filter(
    (c) =>
      c.type === 'text' &&
      (!normalizedQuery || c.name.toLowerCase().includes(normalizedQuery))
  );
  const filteredDmConversations = Array.isArray(dmConversations)
    ? dmConversations.filter((conv) => {
        if (!normalizedQuery) return true;
        const peerName = (
          conv.peerUser?.displayName ||
          conv.peerUser?.username ||
          ''
        ).toLowerCase();
        const preview = (conv.lastMessagePreview || '').toLowerCase();
        return (
          peerName.includes(normalizedQuery) ||
          preview.includes(normalizedQuery)
        );
      })
    : [];

  const handleOpenInviteModal = async () => {
    setIsInviteModalOpen(true);
    setCopiedInvite(false);
    setGeneratedInviteCode('');
    setInviteError('');
    const gid = activeGuildId || activeGuild?.id || 1;
    setIsGeneratingInvite(true);
    try {
      const code = await createInvite(gid);
      if (!code.trim()) throw new Error('The server did not return an invite code.');
      setGeneratedInviteCode(code);
    } catch {
      setInviteError('Không thể tạo mã mời. Hãy thử lại.');
    } finally {
      setIsGeneratingInvite(false);
    }
  };

  const handleCopyInvite = async () => {
    if (!generatedInviteCode) return;
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(generatedInviteCode);
      setCopiedInvite(true);
      setTimeout(() => setCopiedInvite(false), 2000);
    } catch {
      setInviteError('Không thể sao chép mã mời. Hãy sao chép mã thủ công.');
    }
  };

  const handleOpenCreateChannel = () => {
    setNewChannelName('');
    setNewChannelTopic('');
    setIsCreateChannelOpen(true);
  };

  const handleCreateChannelSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const rawName = newChannelName.trim();
    if (!rawName) return;
    const gid = activeGuildId || activeGuild?.id || 1;
    const normalizedName = rawName.toLowerCase().replace(/\s+/g, '-');
    setIsCreatingChannel(true);
    try {
      await createChannel(
        gid,
        normalizedName,
        'text',
        newChannelTopic.trim() || 'Kênh để mọi người cùng trò chuyện'
      );
      setIsCreateChannelOpen(false);
    } finally {
      setIsCreatingChannel(false);
    }
  };

  const openChannelSettings = (channel: Channel) => {
    setEditingChannel(channel);
    setEditedName(channel.name);
    setEditedTopic(channel.topic ?? '');
    setChannelSettingsError('');
    setConfirmChannelDelete(false);
  };

  const handleChannelSettingsSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingChannel || channelSettingsBusy) return;
    const name = editedName.trim();
    if (!name) {
      setChannelSettingsError('Tên kênh không được để trống.');
      return;
    }
    setChannelSettingsBusy(true);
    setChannelSettingsError('');
    try {
      await updateChannel(editingChannel.id, name, editedTopic.trim());
      setEditingChannel(null);
    } catch {
      setChannelSettingsError('Không thể cập nhật kênh. Hãy thử lại.');
    } finally {
      setChannelSettingsBusy(false);
    }
  };

  const handleChannelDelete = async () => {
    if (!editingChannel || channelSettingsBusy || !canDeleteEditingChannel) return;
    setChannelSettingsBusy(true);
    setChannelSettingsError('');
    try {
      await deleteChannel(editingChannel.id);
      setEditingChannel(null);
    } catch {
      setChannelSettingsError('Không thể xóa kênh. Hãy thử lại.');
      setConfirmChannelDelete(false);
    } finally {
      setChannelSettingsBusy(false);
    }
  };

  return (
    <>
      <aside
        aria-label="Danh sách kênh và tin nhắn trực tiếp"
        className="relative flex h-full w-full flex-col select-none font-sans transition-colors duration-200"
      >
        {/* Card 1 Header: Cliq Header + 2 Sub-Tabs (Tin nhắn / Kênh) + Inline Filter */}
        <div className="shrink-0 px-4 pt-4 pb-2.5 border-b border-slate-100 dark:border-slate-800/80">
          <div className="flex items-center justify-between gap-2">
            <div className="min-w-0">
              <h2 className="text-[20px] font-extrabold tracking-tight text-slate-900 dark:text-white truncate">
                {activeTab === 'channels'
                  ? activeGuild?.name || 'Kênh'
                  : 'Tin nhắn'}
              </h2>
            </div>
            {activeTab === 'channels' && (
              <button
                type="button"
                onClick={handleOpenInviteModal}
                title="Mời thành viên vào nhóm"
                className="flex h-7 shrink-0 items-center gap-1 rounded-full bg-blue-600 px-3 text-[11px] font-bold text-white shadow-2xs hover:bg-blue-700 transition cursor-pointer"
              >
                <UserPlus className="h-3.5 w-3.5" />
                <span>Mời</span>
              </button>
            )}
          </div>

          {/* 2 Sub-Tabs: Tin nhắn & Kênh (Channels) — Cliq Segmented Pill Style with Framer Motion */}
          <div className="mt-3 flex items-center gap-1 rounded-2xl border border-slate-200/60 dark:border-slate-800 bg-slate-100/90 dark:bg-slate-900/90 p-1">
            <button
              type="button"
              onClick={() => setViewMode('dm')}
              className={`relative flex-1 rounded-xl px-3 py-1.5 text-xs font-bold transition-colors cursor-pointer ${
                activeTab === 'messages'
                  ? 'text-white'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-100'
              }`}
            >
              {activeTab === 'messages' && (
                <motion.span
                  layoutId="sidebar-mode-pill"
                  className="pointer-events-none absolute inset-0 rounded-xl bg-[#2563eb] shadow-xs"
                  transition={{ type: 'spring', stiffness: 420, damping: 30 }}
                />
              )}
              <span className="relative z-10">Tin nhắn</span>
            </button>
            <button
              type="button"
              onClick={() => setViewMode('guild')}
              className={`relative flex-1 rounded-xl px-3 py-1.5 text-xs font-bold transition-colors cursor-pointer ${
                activeTab === 'channels'
                  ? 'text-white'
                  : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-100'
              }`}
            >
              {activeTab === 'channels' && (
                <motion.span
                  layoutId="sidebar-mode-pill"
                  className="pointer-events-none absolute inset-0 rounded-xl bg-[#2563eb] shadow-xs"
                  transition={{ type: 'spring', stiffness: 420, damping: 30 }}
                />
              )}
              <span className="relative z-10">Kênh</span>
            </button>
          </div>

          {/* Inline Search Filter */}
          <div className="mt-2.5 relative flex items-center">
            <Search className="pointer-events-none absolute left-3 h-3.5 w-3.5 text-slate-400 dark:text-slate-500" />
            <input
              type="text"
              value={filterQuery}
              onChange={(e) => setFilterQuery(e.target.value)}
              placeholder={
                activeTab === 'channels'
                  ? 'Lọc nhanh kênh...'
                  : 'Lọc cuộc trò chuyện...'
              }
              aria-label="Lọc danh sách"
              className="w-full rounded-xl border border-slate-200/80 dark:border-slate-800 bg-slate-50/90 dark:bg-slate-900/70 pl-8 pr-7 py-1.5 text-xs font-medium text-slate-800 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500 outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20"
            />
            {filterQuery && (
              <button
                type="button"
                onClick={() => setFilterQuery('')}
                aria-label="Xóa bộ lọc"
                className="absolute right-2 rounded p-0.5 text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
        </div>

        {/* SCROLLABLE MIDDLE CONTENT */}
        <div className="flex-1 space-y-4 overflow-y-auto px-2.5 py-2.5">
          {activeTab === 'channels' ? (
            <>
              {/* CATEGORY 1: TEXT CHANNELS */}
              <div>
                <div className="mb-1 flex items-center justify-between px-2">
                  <button
                    type="button"
                    onClick={() => setTextCollapsed(!textCollapsed)}
                    className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-[0.08em] text-slate-400 dark:text-slate-500 hover:text-slate-700 dark:hover:text-slate-200 transition cursor-pointer"
                  >
                    {textCollapsed ? (
                      <ChevronRight className="h-3.5 w-3.5" />
                    ) : (
                      <ChevronDown className="h-3.5 w-3.5" />
                    )}
                    <span>KÊNH CHAT VĂN BẢN ({textChannels.length})</span>
                  </button>

                  <button
                    type="button"
                    onClick={handleOpenCreateChannel}
                    title="Tạo kênh văn bản mới"
                    aria-label="Tạo kênh văn bản mới"
                    className="rounded-lg p-1 text-slate-400 dark:text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-[#2563eb] dark:hover:text-blue-400 transition cursor-pointer"
                  >
                    <Plus className="h-3.5 w-3.5" />
                  </button>
                </div>

                {!textCollapsed && (
                  <div className="space-y-1">
                    {textChannels.map((channel) => {
                      const isSelected = activeChannelId === channel.id;
                      const unread = channel.unreadCount ?? 0;

                      return (
                        <div key={channel.id} className="group flex items-center gap-1">
                          <button
                            type="button"
                            onClick={() => selectChannel(channel.id)}
                            className={`group relative flex min-w-0 flex-1 items-center justify-between px-2.5 py-2 rounded-xl text-[13.5px] transition-colors text-left cursor-pointer border ${
                              isSelected
                                ? 'text-[#2563eb] dark:text-blue-400 font-bold border-transparent'
                                : unread > 0
                                ? 'text-slate-900 dark:text-white font-bold hover:bg-slate-100/80 dark:hover:bg-slate-800/60 border-transparent'
                                : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100/80 dark:hover:bg-slate-800/60 font-medium border-transparent'
                            }`}
                          >
                            {isSelected && (
                              <motion.span
                                layoutId="sidebar-active-channel"
                                className="pointer-events-none absolute inset-0 rounded-xl bg-[#eff6ff] dark:bg-blue-600/15 border border-blue-200/60 dark:border-blue-500/25 shadow-2xs"
                                transition={{ type: 'spring', stiffness: 400, damping: 30 }}
                              />
                            )}
                            {/* Unread left dot */}
                            {unread > 0 && !isSelected && (
                              <span className="absolute left-1 z-10 h-1.5 w-1.5 rounded-full bg-[#2563eb]" />
                            )}

                            <div className="relative z-10 flex min-w-0 items-center gap-2.5">
                              <Hash
                                className={`h-4 w-4 shrink-0 ${
                                  isSelected
                                    ? 'text-[#2563eb] dark:text-blue-400'
                                    : 'text-slate-400 dark:text-slate-500'
                                }`}
                              />
                              <span className="truncate">
                                {channel.name}
                              </span>
                            </div>

                            {unread > 0 && (
                              <span className="relative z-10 ml-2 bg-[#2563eb] text-white text-[10px] font-bold px-1.5 py-0.5 rounded-full tabular-nums">
                                {unread > 99 ? '99+' : unread}
                              </span>
                            )}
                          </button>
                          {canManageChannel(channel.id) && (
                            <button
                              type="button"
                              onClick={() => openChannelSettings(channel)}
                              title={`Cài đặt #${channel.name}`}
                              aria-label={`Cài đặt kênh ${channel.name}`}
                              className="rounded-lg p-2 opacity-70 transition hover:opacity-100 focus:opacity-100 text-slate-400 dark:text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800 hover:text-[#2563eb] dark:hover:text-blue-400 cursor-pointer"
                            >
                              <Pencil className="h-3.5 w-3.5" />
                            </button>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </>
          ) : (
            /* FRIENDS & DIRECT MESSAGES SIDEBAR (Cliq Style) */
            <div>
              <div className="mb-1.5 flex items-center justify-between px-2">
                <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-slate-400 dark:text-slate-500">
                  TIN NHẮN TRỰC TIẾP ({filteredDmConversations.length})
                </span>
              </div>

              <div className="space-y-1">
                {filteredDmConversations.length > 0 ? (
                  filteredDmConversations.map((conv: DirectConversation) => {
                    const isActiveDm = viewMode === 'dm' && activeDmId === conv.id;
                    const peer = conv.peerUser;
                    const unread = conv.unreadCount ?? 0;
                    const isTyping =
                      Array.isArray(typingUsers) && typingUsers.some((u) => u.userId === peer?.id);

                    return (
                      <button
                        key={conv.id}
                        type="button"
                        onClick={() => {
                          setViewMode('dm');
                          selectDm(conv.id);
                        }}
                        className={`group relative flex w-full items-center justify-between px-2.5 py-2 rounded-xl text-[13.5px] transition-colors text-left cursor-pointer border ${
                          isActiveDm
                            ? 'text-[#2563eb] dark:text-blue-400 font-bold border-transparent'
                            : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100/80 dark:hover:bg-slate-800/60 font-medium border-transparent'
                        }`}
                      >
                        {isActiveDm && (
                          <motion.span
                            layoutId="sidebar-active-dm"
                            className="pointer-events-none absolute inset-0 rounded-xl bg-[#eff6ff] dark:bg-blue-600/15 border border-blue-200/60 dark:border-blue-500/25 shadow-2xs"
                            transition={{ type: 'spring', stiffness: 400, damping: 30 }}
                          />
                        )}
                        <div className="relative z-10 flex min-w-0 flex-1 items-center gap-2.5">
                          <Avatar
                            name={peer?.displayName || peer?.username || 'U'}
                            color={peer?.avatarColor}
                            status={conv.peerUser?.status}
                            showStatus
                            size="md"
                          />

                          <div className="min-w-0 flex-1">
                            <div className="mb-0.5 flex items-center justify-between gap-1">
                              <span
                                className={`truncate text-[13.5px] font-bold ${
                                  isActiveDm
                                    ? 'text-[#2563eb] dark:text-blue-400'
                                    : 'text-slate-900 dark:text-slate-100'
                                }`}
                              >
                                {peer?.displayName || peer?.username}
                              </span>
                            </div>

                            <div className="flex items-center justify-between gap-1">
                              {isTyping ? (
                                <div className="flex items-center gap-1.5 text-xs font-semibold text-[#2563eb] dark:text-blue-400">
                                  <span className="h-1.5 w-1.5 animate-ping rounded-full bg-[#2563eb]" />
                                  <span>Đang nhập tin nhắn...</span>
                                </div>
                              ) : (
                                <span className="truncate text-[11.5px] font-medium text-slate-500 dark:text-slate-400">
                                  {conv.lastMessagePreview || peer?.customStatus || `@${peer?.username}`}
                                </span>
                              )}

                              {unread > 0 ? (
                                <span className="ml-1 bg-[#2563eb] text-white text-[10px] font-bold px-1.5 py-0.5 rounded-full tabular-nums">
                                  {unread}
                                </span>
                              ) : (
                                <CheckCheck className="h-3.5 w-3.5 shrink-0 text-[#2563eb] dark:text-blue-400 opacity-80" />
                              )}
                            </div>
                          </div>
                        </div>
                      </button>
                    );
                  })
                ) : (
                  <div className="rounded-2xl border border-dashed border-slate-200 dark:border-slate-800 p-4 text-center text-xs text-slate-500 dark:text-slate-400">
                    Chưa có cuộc trò chuyện riêng nào.
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* BOTTOM QUICK STATUS BAR */}
        <div className="shrink-0 flex items-center justify-between gap-2 border-t border-slate-100 dark:border-slate-800/80 px-3.5 py-2 text-[11px] text-slate-500 dark:text-slate-400">
          <div className="flex items-center gap-1.5 min-w-0">
            <span
              className={`h-2 w-2 shrink-0 rounded-full ${
                isConnected ? 'bg-green-500' : 'bg-amber-500'
              }`}
            />
            <span className="truncate font-semibold text-slate-700 dark:text-slate-300">
              {currentUser?.displayName || currentUser?.username || 'Uri User'}
            </span>
          </div>
          <span className="shrink-0 rounded-md bg-blue-50 dark:bg-blue-950/60 px-1.5 py-0.5 font-mono text-[10px] font-bold text-[#2563eb] dark:text-blue-400 border border-blue-200/60 dark:border-blue-800/60">
            {isConnected ? 'Đang kết nối' : 'Đang kết nối lại…'}
          </span>
        </div>
      </aside>

      {/* INVITE CODE MODAL */}
      {isInviteModalOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-xs"
          onClick={() => setIsInviteModalOpen(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className={`w-full max-w-md rounded-2xl border p-6 shadow-2xl ${
              isDark
                ? 'border-slate-800 bg-[#0f172a] text-slate-100'
                : 'border-slate-200 bg-white text-slate-900'
            }`}
          >
            <div className="mb-4 flex items-center justify-between">
              <div>
                <h2 className="text-lg font-bold">
                  Mời Bạn Bè Vào {activeGuild?.name}
                </h2>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  Chia sẻ mã mời dưới đây để đồng đội tham gia máy chủ
                </p>
              </div>
              <button
                type="button"
                onClick={() => setIsInviteModalOpen(false)}
                className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <div
              className={`flex items-center justify-between rounded-2xl border p-3.5 ${
                isDark
                  ? 'border-slate-800 bg-slate-900'
                  : 'border-slate-200 bg-slate-50'
              }`}
            >
              <span className="font-mono text-lg font-bold tracking-widest text-[#2563eb] dark:text-blue-400">
                {isGeneratingInvite
                  ? 'ĐANG TẠO...'
                  : generatedInviteCode || '—'}
              </span>

              <button
                type="button"
                onClick={() => void handleCopyInvite()}
                disabled={!generatedInviteCode || isGeneratingInvite}
                className={`flex items-center gap-1.5 rounded-xl px-3.5 py-2 text-xs font-bold text-white transition cursor-pointer ${
                  copiedInvite
                    ? 'bg-blue-700'
                    : 'bg-blue-600 hover:bg-blue-700'
                }`}
              >
                {copiedInvite ? (
                  <>
                    <Check className="h-4 w-4" />
                    <span>Đã sao chép</span>
                  </>
                ) : (
                  <>
                    <Copy className="h-4 w-4" />
                    <span>Sao chép mã</span>
                  </>
                )}
              </button>
            </div>

            {inviteError && (
              <p role="alert" className="mt-2 text-xs font-semibold text-rose-600 dark:text-rose-400">
                {inviteError}
              </p>
            )}

            <p className="mt-3 text-[11px] text-slate-500 dark:text-slate-400">
              Mã mời có hiệu lực trong 24 giờ (tối đa 25 lượt sử dụng).
            </p>
          </div>
        </div>
      )}

      {/* CREATE CHANNEL MODAL */}
      {isCreateChannelOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-xs"
          onClick={() => setIsCreateChannelOpen(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className={`w-full max-w-md rounded-2xl border p-6 shadow-2xl ${
              isDark
                ? 'border-slate-800 bg-[#0f172a] text-slate-100'
                : 'border-slate-200 bg-white text-slate-900'
            }`}
          >
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-bold">Tạo Kênh Mới</h2>
              <button
                type="button"
                onClick={() => setIsCreateChannelOpen(false)}
                className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <form onSubmit={handleCreateChannelSubmit} className="space-y-4">
              <div>
                <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                  Tên Kênh *
                </label>
                <input
                  type="text"
                  value={newChannelName}
                  onChange={(e) => setNewChannelName(e.target.value)}
                  placeholder="vd: thao-luan-chung"
                  autoFocus
                  className={`w-full rounded-2xl border px-3.5 py-2.5 text-sm font-medium outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 ${
                    isDark
                      ? 'border-slate-700 bg-slate-900 text-white'
                      : 'border-slate-200 bg-slate-50 text-slate-900'
                  }`}
                />
              </div>

              <div>
                <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">
                  Chủ đề kênh (Topic)
                </label>
                <input
                  type="text"
                  value={newChannelTopic}
                  onChange={(e) => setNewChannelTopic(e.target.value)}
                  placeholder="Mô tả nội dung thảo luận của kênh..."
                  className={`w-full rounded-2xl border px-3.5 py-2.5 text-sm font-medium outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 ${
                    isDark
                      ? 'border-slate-700 bg-slate-900 text-white'
                      : 'border-slate-200 bg-slate-50 text-slate-900'
                  }`}
                />
              </div>

              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setIsCreateChannelOpen(false)}
                  className={`rounded-xl px-4 py-2 text-sm font-semibold transition cursor-pointer ${
                    isDark
                      ? 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                      : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={isCreatingChannel}
                  className="rounded-xl bg-blue-600 px-5 py-2 text-sm font-bold text-white shadow-xs shadow-blue-500/25 hover:bg-blue-700 disabled:opacity-50 transition cursor-pointer"
                >
                  {isCreatingChannel ? 'Đang tạo...' : 'Tạo Kênh'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {editingChannel && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-xs"
          onClick={() => !channelSettingsBusy && setEditingChannel(null)}
        >
          <section
            role="dialog"
            aria-modal="true"
            aria-labelledby="channel-settings-title"
            onClick={(event) => event.stopPropagation()}
            className={`w-full max-w-md rounded-2xl border p-6 shadow-2xl ${
              isDark
                ? 'border-slate-800 bg-[#0f172a] text-slate-100'
                : 'border-slate-200 bg-white text-slate-900'
            }`}
          >
            <div className="mb-4 flex items-center justify-between">
              <h2 id="channel-settings-title" className="text-lg font-bold">Cài đặt kênh</h2>
              <button
                type="button"
                onClick={() => setEditingChannel(null)}
                disabled={channelSettingsBusy}
                aria-label="Đóng cài đặt kênh"
                className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 disabled:opacity-50"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <form onSubmit={handleChannelSettingsSubmit} className="space-y-4">
              <div>
                <label htmlFor="channel-settings-name" className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">Tên kênh</label>
                <input
                  id="channel-settings-name"
                  autoFocus
                  required
                  maxLength={80}
                  value={editedName}
                  onChange={(event) => setEditedName(event.target.value)}
                  disabled={channelSettingsBusy}
                  className={`w-full rounded-xl border px-3.5 py-2.5 text-sm outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 ${
                    isDark
                      ? 'border-slate-700 bg-slate-900 text-white'
                      : 'border-slate-200 bg-slate-50 text-slate-900'
                  }`}
                />
              </div>
              <div>
                <label htmlFor="channel-settings-topic" className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">Chủ đề kênh</label>
                <textarea
                  id="channel-settings-topic"
                  rows={3}
                  maxLength={500}
                  value={editedTopic}
                  onChange={(event) => setEditedTopic(event.target.value)}
                  disabled={channelSettingsBusy}
                  className={`w-full resize-y rounded-xl border px-3.5 py-2.5 text-sm outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 ${
                    isDark
                      ? 'border-slate-700 bg-slate-900 text-white'
                      : 'border-slate-200 bg-slate-50 text-slate-900'
                  }`}
                />
              </div>
              {channelSettingsError && <p role="alert" className="text-sm text-rose-600 dark:text-rose-400">{channelSettingsError}</p>}
              <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
                {canDeleteEditingChannel ? (
                  <button
                    type="button"
                    onClick={() => setConfirmChannelDelete(true)}
                    disabled={channelSettingsBusy}
                    className="inline-flex items-center gap-1.5 rounded-xl px-3 py-2 text-sm font-semibold text-rose-600 hover:bg-rose-50 disabled:opacity-50 dark:hover:bg-rose-950/40 cursor-pointer"
                  >
                    <Trash2 className="h-4 w-4" /> Xóa kênh
                  </button>
                ) : <span />}
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setEditingChannel(null)}
                    disabled={channelSettingsBusy}
                    className={`rounded-xl px-4 py-2 text-sm font-semibold disabled:opacity-50 cursor-pointer ${
                      isDark
                        ? 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                        : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                    }`}
                  >Hủy</button>
                  <button
                    type="submit"
                    disabled={channelSettingsBusy || !editedName.trim()}
                    className="rounded-xl bg-blue-600 px-5 py-2 text-sm font-bold text-white shadow-xs shadow-blue-500/25 hover:bg-blue-700 disabled:opacity-50 cursor-pointer"
                  >{channelSettingsBusy ? 'Đang lưu…' : 'Lưu'}</button>
                </div>
              </div>
              {confirmChannelDelete && (
                <div role="alertdialog" aria-labelledby="channel-delete-title" aria-describedby="channel-delete-description" className={`rounded-xl border p-3 ${isDark ? 'border-rose-900 bg-rose-950/30' : 'border-rose-200 bg-rose-50'}`}>
                  <h3 id="channel-delete-title" className="text-sm font-bold">Xóa kênh này?</h3>
                  <p id="channel-delete-description" className="mt-1 text-xs text-slate-600 dark:text-slate-300">Kênh sẽ bị xóa khỏi máy chủ.</p>
                  <div className="mt-3 flex justify-end gap-2">
                    <button type="button" onClick={() => setConfirmChannelDelete(false)} disabled={channelSettingsBusy} className="rounded-lg px-3 py-1.5 text-xs font-semibold cursor-pointer">Giữ lại</button>
                    <button type="button" onClick={handleChannelDelete} disabled={channelSettingsBusy} className="rounded-lg bg-rose-600 px-3 py-1.5 text-xs font-bold text-white hover:bg-rose-700 disabled:opacity-50 cursor-pointer">{channelSettingsBusy ? 'Đang xóa…' : 'Xác nhận xóa'}</button>
                  </div>
                </div>
              )}
            </form>
          </section>
        </div>
      )}
    </>
  );
};

export default ChannelSidebar;
