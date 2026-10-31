import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Home,
  Plus,
  X,
  KeyRound,
  Sparkles,
  Radio,
  Settings,
  Sun,
  Moon,
} from 'lucide-react';
import { useAppStore } from '../../shared/state/appStore';
import { Avatar } from '../../shared/ui/Avatar';
import { userFacingError } from '../../shared/ui/userFacingError';
import type { FriendRequest, PresenceStatus } from '../../shared/types/api';

const PRESENCE_OPTIONS: {
  status: PresenceStatus;
  label: string;
  desc: string;
  dotColor: string;
}[] = [
  {
    status: 'ONLINE',
    label: 'Có mặt',
    desc: 'Sẵn sàng nhận tin nhắn & cuộc gọi',
    dotColor: 'bg-[#10B981]',
  },
  {
    status: 'IDLE',
    label: 'Tạm vắng',
    desc: 'Tạm thời rời bàn phím',
    dotColor: 'bg-[#F59E0B]',
  },
  {
    status: 'DND',
    label: 'Bận',
    desc: 'Không hiện thông báo mới',
    dotColor: 'bg-[#F43F5E]',
  },
  {
    status: 'OFFLINE',
    label: 'Ẩn trạng thái',
    desc: 'Người khác sẽ thấy bạn đang ngoại tuyến',
    dotColor: 'bg-[#94A3B8]',
  },
];

export const GuildRail: React.FC = () => {
  const {
    currentUser,
    theme,
    toggleTheme,
    viewMode,
    setViewMode,
    createGuild,
    joinGuildByInvite,
    friendRequests,
    isSettingsOpen,
    setSettingsOpen,
    updatePresence,
    locale,
  } = useAppStore();

  const [hoveredId, setHoveredId] = useState<string | number | null>(null);
  const [isPresencePickerOpen, setIsPresencePickerOpen] = useState(false);
  const [customStatusInput, setCustomStatusInput] = useState('');
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isJoinModalOpen, setIsJoinModalOpen] = useState(false);

  const [newGuildName, setNewGuildName] = useState('');
  const [newGuildIcon, setNewGuildIcon] = useState('');
  const [inviteCode, setInviteCode] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);

  const isDark = theme === 'dark';

  const activeDockOption = isSettingsOpen
    ? 'settings'
    : isCreateModalOpen || isJoinModalOpen
      ? 'add-server'
      : viewMode === 'friends'
        ? 'home'
        : null;

  // Calculate pending incoming friend requests count
  const pendingIncomingCount = Array.isArray(friendRequests)
    ? friendRequests.filter(
        (req: FriendRequest) => req.direction === 'INCOMING'
      ).length
    : 0;

  const handleOpenCreateModal = () => {
    setNewGuildName('');
    setNewGuildIcon('');
    setModalError(null);
    setIsCreateModalOpen(true);
  };

  const handleOpenJoinModal = () => {
    setInviteCode('');
    setModalError(null);
    setIsJoinModalOpen(true);
  };

  const handleCreateGuildSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedName = newGuildName.trim();
    if (!trimmedName) {
      setModalError('Vui lòng nhập tên máy chủ.');
      return;
    }
    const initials =
      newGuildIcon.trim().toUpperCase().slice(0, 3) ||
      trimmedName
        .split(/\s+/)
        .map((w) => w[0])
        .join('')
        .toUpperCase()
        .slice(0, 2) ||
      'SV';

    setIsSubmitting(true);
    setModalError(null);
    try {
      await createGuild(trimmedName, initials);
      setIsCreateModalOpen(false);
    } catch (err) {
      setModalError(
        userFacingError(err, { locale, action: 'create guild', fallback: 'Không thể tạo máy chủ lúc này. Vui lòng thử lại.', fallbackEn: 'The group could not be created. Please try again.' })
      );
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleJoinGuildSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedCode = inviteCode.trim();
    if (!trimmedCode) {
      setModalError('Vui lòng nhập mã mời.');
      return;
    }
    setIsSubmitting(true);
    setModalError(null);
    try {
      await joinGuildByInvite(trimmedCode);
      setIsJoinModalOpen(false);
    } catch (err) {
      setModalError(
        userFacingError(err, { locale, action: 'join guild', fallback: 'Không thể tham gia máy chủ. Hãy kiểm tra mã mời rồi thử lại.', fallbackEn: 'You could not join the group. Check the invite and try again.' })
      );
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <>
      <aside
        aria-label="Thanh máy chủ và điều hướng chính"
        className="dock-rail-shell select-none"
      >
        {/* Top: Uri Candy Swirl Brand Logo (Standalone, No White Badge) */}
        <div
          className="relative flex w-full shrink-0 items-center justify-center"
          onMouseEnter={() => setHoveredId('uri-brand')}
          onMouseLeave={() => setHoveredId(null)}
        >
          <motion.button
            type="button"
            whileHover={{ scale: 1.08, y: -1 }}
            whileTap={{ scale: 0.93 }}
            transition={{ type: 'spring', stiffness: 420, damping: 24 }}
            className="relative flex h-12 w-12 items-center justify-center cursor-pointer bg-transparent border-0 p-0 focus:outline-none"
            title="Trang chủ Uri"
            aria-label="Trang chủ Uri"
            onClick={() => setViewMode('friends')}
          >
            <img
              src="/uri-logo.png"
              alt="Uri"
              className="h-11 w-11 object-contain select-none drop-shadow-xs"
              draggable={false}
            />
          </motion.button>
          <AnimatePresence>
            {hoveredId === 'uri-brand' && (
              <motion.div
                initial={{ opacity: 0, x: -6, scale: 0.95 }}
                animate={{ opacity: 1, x: 0, scale: 1 }}
                exit={{ opacity: 0, x: -4, scale: 0.95 }}
                transition={{ duration: 0.14 }}
                className="pointer-events-none fixed left-[78px] z-50 rounded-xl bg-slate-900 px-2.5 py-1 text-xs font-bold text-white shadow-lg"
              >
                Trang chủ Uri
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        {/* Center: Vertically Centered Circular Glass Option Badges */}
        <div className="my-auto flex w-full flex-col items-center justify-center gap-3.5 py-3">
          {/* 1. Home / Friends Button */}
          <div
            className="relative flex w-full items-center justify-center"
            onMouseEnter={() => setHoveredId('home')}
            onMouseLeave={() => setHoveredId(null)}
          >
            <motion.button
              type="button"
              whileHover={{ scale: 1.07, y: -1 }}
              whileTap={{ scale: 0.93 }}
              transition={{ type: 'spring', stiffness: 420, damping: 24 }}
              onClick={() => setViewMode('friends')}
              aria-label="Trang chủ / Bạn bè"
              className="dock-glass-badge"
            >
              {activeDockOption === 'home' && (
                <motion.span
                  layoutId="dock-active-pill"
                  className="
                    pointer-events-none absolute inset-0 rounded-full
                    bg-gradient-to-br from-[#3b82f6] to-[#2563eb]
                    shadow-[0_6px_18px_rgba(37,99,235,0.38),inset_0_1px_1px_rgba(255,255,255,0.38)]
                  "
                  transition={{ type: 'spring', stiffness: 380, damping: 26 }}
                />
              )}
              <Home
                className={`relative z-10 h-5 w-5 transition-colors duration-150 ${
                  activeDockOption === 'home' ? 'text-white' : ''
                }`}
                strokeWidth={1.9}
              />
              {pendingIncomingCount > 0 && (
                <span className="absolute -right-1 -top-1 z-20 flex h-4 min-w-[16px] items-center justify-center rounded-full bg-[#F43F5E] px-1 text-[9px] font-bold text-white shadow-xs ring-2 ring-white dark:ring-slate-900">
                  {pendingIncomingCount}
                </span>
              )}
            </motion.button>
            <AnimatePresence>
              {hoveredId === 'home' && (
                <motion.div
                  initial={{ opacity: 0, x: -6, scale: 0.95 }}
                  animate={{ opacity: 1, x: 0, scale: 1 }}
                  exit={{ opacity: 0, x: -4, scale: 0.95 }}
                  transition={{ duration: 0.14 }}
                  className="pointer-events-none fixed left-[78px] z-50 rounded-xl bg-slate-900 px-2.5 py-1 text-xs font-semibold text-white shadow-lg"
                >
                  Trang chủ / Bạn bè
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          {/* 2. Plus Button (Add / Create Guild) */}
          <div
            className="relative flex w-full items-center justify-center"
            onMouseEnter={() => setHoveredId('add-server')}
            onMouseLeave={() => setHoveredId(null)}
          >
            <motion.button
              type="button"
              whileHover={{ scale: 1.07, y: -1 }}
              whileTap={{ scale: 0.93 }}
              transition={{ type: 'spring', stiffness: 420, damping: 24 }}
              onClick={handleOpenCreateModal}
              aria-label="Tạo máy chủ mới"
              className="dock-glass-badge"
            >
              {activeDockOption === 'add-server' && (
                <motion.span
                  layoutId="dock-active-pill"
                  className="
                    pointer-events-none absolute inset-0 rounded-full
                    bg-gradient-to-br from-[#3b82f6] to-[#2563eb]
                    shadow-[0_6px_18px_rgba(37,99,235,0.38),inset_0_1px_1px_rgba(255,255,255,0.38)]
                  "
                  transition={{ type: 'spring', stiffness: 380, damping: 26 }}
                />
              )}
              <Plus
                className={`relative z-10 h-5 w-5 transition-colors duration-150 ${
                  activeDockOption === 'add-server' ? 'text-white' : ''
                }`}
                strokeWidth={2.1}
              />
            </motion.button>
            <AnimatePresence>
              {hoveredId === 'add-server' && (
                <motion.div
                  initial={{ opacity: 0, x: -6, scale: 0.95 }}
                  animate={{ opacity: 1, x: 0, scale: 1 }}
                  exit={{ opacity: 0, x: -4, scale: 0.95 }}
                  transition={{ duration: 0.14 }}
                  className="pointer-events-none fixed left-[78px] z-50 rounded-xl bg-slate-900 px-2.5 py-1 text-xs font-semibold text-white shadow-lg"
                >
                  Tạo máy chủ mới
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          {/* 3. Settings / Gear */}
          <div
            className="relative flex w-full items-center justify-center"
            onMouseEnter={() => setHoveredId('settings')}
            onMouseLeave={() => setHoveredId(null)}
          >
            <motion.button
              type="button"
              whileHover={{ scale: 1.07, y: -1, rotate: 18 }}
              whileTap={{ scale: 0.93 }}
              transition={{ type: 'spring', stiffness: 420, damping: 24 }}
              onClick={() => setSettingsOpen(true)}
              aria-label="Cài đặt hệ thống"
              className="dock-glass-badge"
            >
              {activeDockOption === 'settings' && (
                <motion.span
                  layoutId="dock-active-pill"
                  className="
                    pointer-events-none absolute inset-0 rounded-full
                    bg-gradient-to-br from-[#3b82f6] to-[#2563eb]
                    shadow-[0_6px_18px_rgba(37,99,235,0.38),inset_0_1px_1px_rgba(255,255,255,0.38)]
                  "
                  transition={{ type: 'spring', stiffness: 380, damping: 26 }}
                />
              )}
              <Settings
                className={`relative z-10 h-5 w-5 transition-colors duration-150 ${
                  activeDockOption === 'settings' ? 'text-white' : ''
                }`}
                strokeWidth={1.9}
              />
            </motion.button>
            <AnimatePresence>
              {hoveredId === 'settings' && (
                <motion.div
                  initial={{ opacity: 0, x: -6, scale: 0.95 }}
                  animate={{ opacity: 1, x: 0, scale: 1 }}
                  exit={{ opacity: 0, x: -4, scale: 0.95 }}
                  transition={{ duration: 0.14 }}
                  className="pointer-events-none fixed left-[78px] z-50 rounded-xl bg-slate-900 px-2.5 py-1 text-xs font-semibold text-white shadow-lg"
                >
                  Cài đặt hệ thống
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          {/* 4. Theme Toggle (Light / Dark Mode) */}
          <div
            className="relative flex w-full items-center justify-center"
            onMouseEnter={() => setHoveredId('theme-toggle')}
            onMouseLeave={() => setHoveredId(null)}
          >
            <motion.button
              type="button"
              whileHover={{ scale: 1.07, y: -1 }}
              whileTap={{ scale: 0.93 }}
              transition={{ type: 'spring', stiffness: 420, damping: 24 }}
              onClick={toggleTheme}
              aria-label={
                isDark
                  ? 'Chuyển sang giao diện Sáng (Light Mode)'
                  : 'Chuyển sang giao diện Tối (Dark Mode)'
              }
              className="dock-glass-badge"
            >
              <AnimatePresence mode="wait" initial={false}>
                {isDark ? (
                  <motion.span
                    key="sun"
                    initial={{ rotate: -90, opacity: 0, scale: 0.6 }}
                    animate={{ rotate: 0, opacity: 1, scale: 1 }}
                    exit={{ rotate: 90, opacity: 0, scale: 0.6 }}
                    transition={{ duration: 0.18 }}
                    className="relative z-10 flex items-center justify-center"
                  >
                    <Sun
                      className="h-5 w-5 text-amber-400"
                      strokeWidth={1.95}
                    />
                  </motion.span>
                ) : (
                  <motion.span
                    key="moon"
                    initial={{ rotate: 90, opacity: 0, scale: 0.6 }}
                    animate={{ rotate: 0, opacity: 1, scale: 1 }}
                    exit={{ rotate: -90, opacity: 0, scale: 0.6 }}
                    transition={{ duration: 0.18 }}
                    className="relative z-10 flex items-center justify-center"
                  >
                    <Moon
                      className="h-5 w-5 text-slate-600"
                      strokeWidth={1.95}
                    />
                  </motion.span>
                )}
              </AnimatePresence>
            </motion.button>
            <AnimatePresence>
              {hoveredId === 'theme-toggle' && (
                <motion.div
                  initial={{ opacity: 0, x: -6, scale: 0.95 }}
                  animate={{ opacity: 1, x: 0, scale: 1 }}
                  exit={{ opacity: 0, x: -4, scale: 0.95 }}
                  transition={{ duration: 0.14 }}
                  className="pointer-events-none fixed left-[78px] z-50 rounded-xl bg-slate-900 px-2.5 py-1 text-xs font-semibold text-white shadow-lg"
                >
                  {isDark ? 'Giao diện Sáng (Light)' : 'Giao diện Tối (Dark)'}
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </div>

        {/* Bottom: Current User Avatar with Online Status Dot */}
        <div className="relative flex shrink-0 flex-col items-center">
          <motion.button
            type="button"
            whileHover={{ scale: 1.06 }}
            whileTap={{ scale: 0.94 }}
            transition={{ type: 'spring', stiffness: 420, damping: 24 }}
            onClick={() => {
              setCustomStatusInput(currentUser?.customStatus || '');
              setIsPresencePickerOpen(!isPresencePickerOpen);
            }}
            className="flex h-11 w-11 items-center justify-center rounded-full bg-white/80 dark:bg-slate-800/80 backdrop-blur-md border border-slate-200/90 dark:border-slate-700/80 shadow-xs cursor-pointer focus:outline-none focus:ring-2 focus:ring-blue-500/40"
            title={`${currentUser?.displayName || 'Tài khoản'} (Đổi trạng thái)`}
            aria-label={`${currentUser?.displayName || 'Tài khoản'} (Đổi trạng thái)`}
          >
            <Avatar
              name={currentUser?.displayName || currentUser?.username || 'Uri'}
              color={currentUser?.avatarColor}
              size="md"
              status={currentUser?.status || 'ONLINE'}
              showStatus
            />
          </motion.button>

          {/* Quick Presence Popover Menu */}
          {isPresencePickerOpen && (
            <div
              className={`fixed left-[76px] bottom-5 z-50 w-[240px] rounded-2xl border p-3 shadow-2xl backdrop-blur-md ${
                isDark
                  ? 'border-slate-800 bg-[#0f172a]/95 text-slate-100'
                  : 'border-slate-200/80 bg-white/95 text-slate-900'
              }`}
            >
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-[11px] font-bold tracking-wider uppercase text-slate-400 dark:text-slate-500">
                    {currentUser?.displayName || 'Tài khoản'}
                  </span>
                  <button
                    type="button"
                    onClick={() => setIsPresencePickerOpen(false)}
                    className="rounded p-0.5 text-slate-400 hover:text-slate-900 dark:hover:text-white"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>

                <div className="space-y-1">
                  {PRESENCE_OPTIONS.map((opt) => {
                    const isCurrent = currentUser?.status === opt.status;
                    return (
                      <button
                        key={opt.status}
                        type="button"
                        onClick={() => {
                          updatePresence(
                            opt.status,
                            customStatusInput.trim() || undefined
                          );
                          setIsPresencePickerOpen(false);
                        }}
                        className={`flex w-full items-start gap-2.5 rounded-xl p-2 text-left transition ${
                          isCurrent
                            ? 'bg-[#eff6ff] text-blue-600 dark:bg-blue-600/15 dark:text-blue-400'
                            : 'hover:bg-slate-100/80 dark:hover:bg-slate-800/60'
                        }`}
                      >
                        <span
                          className={`mt-1 h-2.5 w-2.5 shrink-0 rounded-full ${opt.dotColor}`}
                        />
                        <div className="min-w-0">
                          <div className="text-xs font-bold">{opt.label}</div>
                          <div className="truncate text-[10px] text-slate-500 dark:text-slate-400">
                            {opt.desc}
                          </div>
                        </div>
                      </button>
                    );
                  })}
                </div>
              </div>
            )}
        </div>
      </aside>

      {/* Create Guild Modal */}
      {isCreateModalOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-xs"
          onClick={() => setIsCreateModalOpen(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className={`w-full max-w-md rounded-2xl border p-6 shadow-2xl transition-all ${
              isDark
                ? 'border-slate-800 bg-[#0f172a] text-slate-100'
                : 'border-slate-200 bg-white text-slate-900'
            }`}
          >
            <div className="mb-4 flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-[#eff6ff] text-blue-600 dark:bg-blue-950/60 dark:text-blue-400">
                  <Sparkles className="h-5 w-5" />
                </div>
                <div>
                  <h2 className="text-lg font-bold">Tạo máy chủ mới</h2>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    Uri sẽ tạo sẵn một kênh trò chuyện và một phòng thoại cho bạn.
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsCreateModalOpen(false)}
                className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
                aria-label="Đóng"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <form onSubmit={handleCreateGuildSubmit} className="space-y-4">
              <div>
                <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-300">
                  Tên Máy Chủ *
                </label>
                <input
                  type="text"
                  value={newGuildName}
                  onChange={(e) => setNewGuildName(e.target.value)}
                  placeholder="VD: Nhóm Đồ Án Lập Trình Mạng"
                  autoFocus
                  className={`w-full rounded-xl border px-3.5 py-2.5 text-sm font-medium outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 ${
                    isDark
                      ? 'border-slate-700 bg-slate-900 text-white placeholder-slate-500'
                      : 'border-slate-300 bg-slate-50 text-slate-900 placeholder-slate-400'
                  }`}
                />
              </div>

              <div>
                <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-300">
                  Ký tự biểu tượng (Tối đa 3 ký tự)
                </label>
                <div className="flex items-center gap-3">
                  <input
                    type="text"
                    maxLength={3}
                    value={newGuildIcon}
                    onChange={(e) =>
                      setNewGuildIcon(e.target.value.toUpperCase())
                    }
                    placeholder="VD: LTM"
                    className={`w-full rounded-xl border px-3.5 py-2.5 text-sm font-bold uppercase outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 ${
                      isDark
                        ? 'border-slate-700 bg-slate-900 text-white placeholder-slate-500'
                        : 'border-slate-300 bg-slate-50 text-slate-900 placeholder-slate-400'
                    }`}
                  />
                  <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-blue-600 text-sm font-bold text-white shadow-sm shadow-blue-500/25">
                    {newGuildIcon.trim() ||
                      newGuildName
                        .trim()
                        .split(/\s+/)
                        .map((w) => w[0])
                        .join('')
                        .toUpperCase()
                        .slice(0, 2) ||
                      'SV'}
                  </div>
                </div>
              </div>

              {modalError && (
                <div className="rounded-xl bg-[#FFE4E6] px-3 py-2 text-xs font-semibold text-[#F43F5E]">
                  {modalError}
                </div>
              )}

              <div className="flex items-center justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => {
                    setIsCreateModalOpen(false);
                    handleOpenJoinModal();
                  }}
                  className="mr-auto text-xs font-semibold text-blue-600 dark:text-blue-400 hover:underline"
                >
                  Tham gia bằng mã mời
                </button>
                <button
                  type="button"
                  onClick={() => setIsCreateModalOpen(false)}
                  className={`rounded-xl px-4 py-2 text-sm font-semibold transition ${
                    isDark
                      ? 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                      : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting}
                  className="rounded-xl bg-blue-600 px-5 py-2 text-sm font-bold text-white shadow-xs shadow-blue-500/25 transition hover:bg-blue-700 disabled:opacity-50"
                >
                  {isSubmitting ? 'Đang tạo...' : 'Tạo Máy Chủ'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Join Guild by Invite Code Modal */}
      {isJoinModalOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-xs"
          onClick={() => setIsJoinModalOpen(false)}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className={`w-full max-w-md rounded-2xl border p-6 shadow-2xl transition-all ${
              isDark
                ? 'border-slate-800 bg-[#0f172a] text-slate-100'
                : 'border-slate-200 bg-white text-slate-900'
            }`}
          >
            <div className="mb-4 flex items-center justify-between">
              <div className="flex items-center gap-2.5">
                <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-[#eff6ff] text-blue-600 dark:bg-blue-950/60 dark:text-blue-400">
                  <KeyRound className="h-5 w-5" />
                </div>
                <div>
                  <h2 className="text-lg font-bold">Tham Gia Bằng Mã Mời</h2>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    Nhập mã mời 6–8 ký tự để kết nối vào máy chủ của nhóm
                  </p>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsJoinModalOpen(false)}
                className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
                aria-label="Đóng"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <form onSubmit={handleJoinGuildSubmit} className="space-y-4">
              <div>
                <label className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-600 dark:text-slate-300">
                  Mã mời máy chủ *
                </label>
                <input
                  type="text"
                  value={inviteCode}
                  onChange={(e) => setInviteCode(e.target.value.toUpperCase())}
                  placeholder="VD: LTM2026"
                  autoFocus
                  className={`w-full rounded-xl border px-3.5 py-2.5 font-mono text-sm font-bold tracking-wider uppercase outline-none transition focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 ${
                    isDark
                      ? 'border-slate-700 bg-slate-900 text-white placeholder-slate-500'
                      : 'border-slate-300 bg-slate-50 text-slate-900 placeholder-slate-400'
                  }`}
                />
                <div className="mt-2 flex items-center gap-1.5 text-[11px] text-slate-500 dark:text-slate-400">
                  <Radio className="h-3.5 w-3.5 text-blue-600 dark:text-blue-400" />
                  <span>
                    Các kênh trong không gian này sẽ được chia sẻ tự động với thành viên.
                  </span>
                </div>
              </div>

              {modalError && (
                <div className="rounded-xl bg-[#FFE4E6] px-3 py-2 text-xs font-semibold text-[#F43F5E]">
                  {modalError}
                </div>
              )}

              <div className="flex items-center justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setIsJoinModalOpen(false)}
                  className={`rounded-xl px-4 py-2 text-sm font-semibold transition ${
                    isDark
                      ? 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                      : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                >
                  Hủy
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting}
                  className="rounded-xl bg-blue-600 px-5 py-2 text-sm font-bold text-white shadow-xs shadow-blue-500/25 transition hover:bg-blue-700 disabled:opacity-50"
                >
                  {isSubmitting ? 'Đang tham gia...' : 'Tham Gia Ngay'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
};

export default GuildRail;
