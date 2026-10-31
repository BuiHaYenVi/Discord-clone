import React, { useState } from 'react';
import {
  Crown,
  Shield,
  MessageSquare,
  UserPlus,
  UserMinus,
  Check,
  X,
  Sparkles,
  ChevronDown,
  ChevronRight,
} from 'lucide-react';
import { useAppStore } from '../../shared/state/appStore';
import { Avatar } from '../../shared/ui/Avatar';
import type { MemberRole, PresenceStatus } from '../../shared/types/api';

interface NormalizedMember {
  userId: number;
  username: string;
  displayName: string;
  role: MemberRole;
  status: PresenceStatus;
  customStatus?: string | null;
  avatarColor?: string;
  transport?: string;
}

export const MemberSidebar: React.FC = () => {
  const store = useAppStore();
  const {
    currentUser,
    theme,
    activeChannelId,
    channelMembersById,
    members,
    friends,
    isMemberSidebarOpen,
    setMemberSidebarOpen,
    openDmWithUser,
    sendFriendRequest,
    updateMemberRole,
    setViewMode,
  } = store;

  const [selectedMemberId, setSelectedMemberId] = useState<number | null>(null);
  const [sentRequestIds, setSentRequestIds] = useState<number[]>([]);
  const [onlineCollapsed, setOnlineCollapsed] = useState(false);
  const [offlineCollapsed, setOfflineCollapsed] = useState(false);

  if (!isMemberSidebarOpen) {
    return null;
  }

  const isDark = theme === 'dark';

  // Normalize member objects so whether store passes ChannelMember or User-like objects, it works seamlessly
  const normalizedMembers: NormalizedMember[] = (Array.isArray(members)
    ? members
    : []
  ).map((raw) => {
    const r = raw as unknown as Record<string, unknown>;
    const uid =
      typeof r.userId === 'number'
        ? r.userId
        : typeof r.id === 'number'
        ? r.id
        : 0;
    const roleVal = (r.role as MemberRole) || 'MEMBER';
    const statusVal = (r.status as PresenceStatus) || 'ONLINE';
    return {
      userId: uid,
      username: String(r.username || 'user'),
      displayName: String(r.displayName || r.username || 'Thành viên'),
      role: roleVal,
      status: statusVal,
      customStatus: (r.customStatus as string | null | undefined) ?? null,
      avatarColor: (r.avatarColor as string | undefined) || undefined,
      transport: (r.transport as string | undefined) || 'WS',
    };
  });

  // Check if currentUser is OWNER or ADMIN
  const activeChannelMembers = channelMembersById[activeChannelId];
  const currentChannelRole = Array.isArray(activeChannelMembers)
    ? activeChannelMembers.find((member) => member.id === currentUser?.id)?.role
    : undefined;
  const isCurrentUserOwner = currentChannelRole === 'OWNER';
  const isCurrentUserAdmin = isCurrentUserOwner || currentChannelRole === 'ADMIN';

  // Group online members by role, and offline members separately
  const onlineOwners = normalizedMembers.filter(
    (m) => m.status !== 'OFFLINE' && m.role === 'OWNER'
  );
  const onlineAdmins = normalizedMembers.filter(
    (m) => m.status !== 'OFFLINE' && m.role === 'ADMIN'
  );
  const onlineRegulars = normalizedMembers.filter(
    (m) => m.status !== 'OFFLINE' && m.role === 'MEMBER'
  );
  const onlineMembers = [
    ...onlineOwners,
    ...onlineAdmins,
    ...onlineRegulars,
  ];
  const offlineMembers = normalizedMembers.filter(
    (m) => m.status === 'OFFLINE'
  );

  const selectedMember =
    normalizedMembers.find((m) => m.userId === selectedMemberId) || null;

  const isAlreadyFriend = (uid: number, uname: string) => {
    if (!Array.isArray(friends)) return false;
    return friends.some((f) => f.id === uid || f.username === uname);
  };

  const handleOpenDm = async (member: NormalizedMember) => {
    await Promise.resolve(openDmWithUser(member.userId));
    setViewMode('dm');
    setSelectedMemberId(null);
  };

  const handleAddFriend = async (member: NormalizedMember) => {
    await Promise.resolve(sendFriendRequest(member.username));
    setSentRequestIds((prev) => [...prev, member.userId]);
  };

  const handleRoleChange = async (member: NormalizedMember, role: MemberRole) => {
    if (
      role === 'OWNER' &&
      member.role !== 'OWNER' &&
      !window.confirm(`Chuyển quyền quản lý nhóm cho ${member.displayName}? Bạn sẽ không còn là người quản lý chính.`)
    ) {
      return;
    }
    await Promise.resolve(updateMemberRole(member.userId, role));
  };

  const renderRoleBadge = (role: MemberRole) => {
    if (role === 'OWNER') {
      return (
        <span className="inline-flex items-center gap-1 rounded-md border border-amber-200/70 dark:border-amber-800/60 bg-amber-50 dark:bg-amber-950/60 px-1.5 py-0.5 text-[10px] font-bold text-amber-600 dark:text-amber-400 shrink-0">
          <Crown className="h-3 w-3" />
          <span>Chủ nhóm</span>
        </span>
      );
    }
    if (role === 'ADMIN') {
      return (
        <span className="inline-flex items-center gap-1 rounded-md border border-blue-200/70 dark:border-blue-800/60 bg-blue-50 dark:bg-blue-950/60 px-1.5 py-0.5 text-[10px] font-bold text-blue-600 dark:text-blue-400 shrink-0">
          <Shield className="h-3 w-3" />
          <span>Quản trị viên</span>
        </span>
      );
    }
    return null;
  };

  const renderMemberRow = (member: NormalizedMember) => {
    const isSelected = selectedMemberId === member.userId;
    const isOffline = member.status === 'OFFLINE';

    return (
      <button
        key={member.userId}
        type="button"
        onClick={() =>
          setSelectedMemberId(
            selectedMemberId === member.userId ? null : member.userId
          )
        }
        className={`group flex w-full items-center justify-between px-2.5 py-2 rounded-xl text-left transition-all cursor-pointer border ${
          isSelected
            ? 'bg-[#eff6ff] dark:bg-blue-600/15 text-[#2563eb] dark:text-blue-400 border-blue-200/60 dark:border-blue-500/25 shadow-2xs'
            : 'hover:bg-slate-100/80 dark:hover:bg-slate-800/60 border-transparent'
        } ${isOffline ? 'opacity-65 hover:opacity-100' : ''}`}
      >
        <div className="flex min-w-0 items-center gap-2.5">
          <Avatar
            name={member.displayName}
            color={member.avatarColor}
            status={member.status}
            showStatus
            size="sm"
          />

          <div className="min-w-0">
            <div className="flex items-center gap-1.5">
              <span
                className={`truncate text-[13px] font-bold ${
                  isSelected
                    ? 'text-[#2563eb] dark:text-blue-400'
                    : 'text-slate-900 dark:text-slate-100'
                }`}
              >
                {member.displayName}
              </span>
              {renderRoleBadge(member.role)}
            </div>

            <div
              className={`truncate text-[11px] font-medium ${
                isSelected
                  ? 'text-blue-600/80 dark:text-blue-300/80'
                  : 'text-slate-500 dark:text-slate-400'
              }`}
            >
              {member.customStatus || `@${member.username}`}
            </div>
          </div>
        </div>

      </button>
    );
  };

  return (
    <aside
      aria-label="Danh sách thành viên kênh"
      className="relative flex h-full w-full flex-col select-none font-sans transition-colors duration-200"
    >
      {/* Top Summary Header (60px) */}
      <div className="flex h-[60px] shrink-0 items-center justify-between border-b border-slate-200/80 dark:border-slate-800 px-4">
        <div className="flex items-center gap-2">
          <span className="text-xs font-bold tracking-wider uppercase text-slate-700 dark:text-slate-200">
            Thành viên
          </span>
          <span className="rounded-full bg-[#eff6ff] dark:bg-blue-950/60 border border-blue-200/70 dark:border-blue-800/60 px-2 py-0.5 text-[11px] font-bold text-[#2563eb] dark:text-blue-400 tabular-nums">
            {normalizedMembers.length}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-1 rounded-full bg-emerald-50 dark:bg-emerald-950/50 border border-emerald-200/70 dark:border-emerald-800/60 px-2 py-0.5 font-mono text-[10px] font-bold text-emerald-600 dark:text-emerald-400 tabular-nums">
            <span className="h-1.5 w-1.5 rounded-full bg-green-500" />
            {onlineMembers.length} Online
          </span>
          <button
            type="button"
            onClick={() => setMemberSidebarOpen(false)}
            className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-900 dark:hover:bg-slate-800 dark:hover:text-white transition cursor-pointer"
            title="Đóng danh sách thành viên"
            aria-label="Đóng danh sách thành viên"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Scrollable Online / Role / Offline Groups */}
      <div className="flex-1 space-y-4 overflow-y-auto px-2.5 py-3">
        {/* ONLINE SECTION */}
        <div>
          <button
            type="button"
            onClick={() => setOnlineCollapsed(!onlineCollapsed)}
            className="mb-1.5 flex w-full items-center justify-between px-2 text-[11px] font-bold uppercase tracking-[0.08em] text-slate-400 dark:text-slate-500 hover:text-slate-700 dark:hover:text-slate-200 transition cursor-pointer"
          >
            <span className="flex items-center gap-1.5">
              {onlineCollapsed ? (
                <ChevronRight className="h-3.5 w-3.5" />
              ) : (
                <ChevronDown className="h-3.5 w-3.5" />
              )}
              <span>ONLINE — TRỰC TUYẾN ({onlineMembers.length})</span>
            </span>
          </button>

          {!onlineCollapsed && (
            <div className="space-y-3">
              {/* OWNER GROUP */}
              {onlineOwners.length > 0 && (
                <div>
                  <div className="mb-1 px-2 text-[11px] font-bold uppercase tracking-[0.08em] text-amber-500 dark:text-amber-400">
                    Chủ nhóm ({onlineOwners.length})
                  </div>
                  <div className="space-y-0.5">
                    {onlineOwners.map(renderMemberRow)}
                  </div>
                </div>
              )}

              {/* ADMIN GROUP */}
              {onlineAdmins.length > 0 && (
                <div>
                  <div className="mb-1 px-2 text-[11px] font-bold uppercase tracking-[0.08em] text-blue-600 dark:text-blue-400">
                    Quản trị viên ({onlineAdmins.length})
                  </div>
                  <div className="space-y-0.5">
                    {onlineAdmins.map(renderMemberRow)}
                  </div>
                </div>
              )}

              {/* MEMBER GROUP */}
              {onlineRegulars.length > 0 && (
                <div>
                  <div className="mb-1 px-2 text-[11px] font-bold uppercase tracking-[0.08em] text-slate-400 dark:text-slate-500">
                    Thành viên ({onlineRegulars.length})
                  </div>
                  <div className="space-y-0.5">
                    {onlineRegulars.map(renderMemberRow)}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        {/* OFFLINE GROUP */}
        {offlineMembers.length > 0 && (
          <div>
            <button
              type="button"
              onClick={() => setOfflineCollapsed(!offlineCollapsed)}
              className="mb-1.5 flex w-full items-center justify-between px-2 text-[11px] font-bold uppercase tracking-[0.08em] text-slate-400 dark:text-slate-500 hover:text-slate-700 dark:hover:text-slate-200 transition cursor-pointer"
            >
              <span className="flex items-center gap-1.5">
                {offlineCollapsed ? (
                  <ChevronRight className="h-3.5 w-3.5" />
                ) : (
                  <ChevronDown className="h-3.5 w-3.5" />
                )}
                <span>Ngoại tuyến ({offlineMembers.length})</span>
              </span>
            </button>
            {!offlineCollapsed && (
              <div className="space-y-0.5">
                {offlineMembers.map(renderMemberRow)}
              </div>
            )}
          </div>
        )}
      </div>

      {/* FLOATING USER PROFILE POPOVER CARD */}
      {selectedMember && (
        <div
          className={`absolute top-16 right-[268px] z-50 w-[280px] overflow-hidden rounded-3xl border shadow-2xl transition-all ${
            isDark
              ? 'border-slate-800 bg-[#0f172a] text-slate-100'
              : 'border-slate-200/80 bg-white text-slate-900'
          }`}
        >
          {/* Top Cliq Royal Blue Banner */}
          <div className="relative h-16 bg-gradient-to-r from-[#2563eb] to-blue-700">
            <button
              type="button"
              onClick={() => setSelectedMemberId(null)}
              className="absolute top-2 right-2 rounded-full bg-slate-950/30 p-1 text-white hover:bg-slate-950/50 cursor-pointer"
              aria-label="Đóng hồ sơ"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          <div className="px-4 pb-4">
            {/* Avatar overlapping banner */}
            <div className="-mt-8 mb-3 flex items-end justify-between">
              <div className="rounded-full ring-4 ring-white dark:ring-[#0f172a]">
                <Avatar
                  name={selectedMember.displayName}
                  color={selectedMember.avatarColor}
                  status={selectedMember.status}
                  showStatus
                  size="2xl"
                />
              </div>

              {/* Role Badge */}
              <div className="flex items-center gap-1">
                {selectedMember.role === 'OWNER' && (
                  <span className="inline-flex items-center gap-1 rounded-full border border-amber-200/70 dark:border-amber-800/60 bg-amber-50 dark:bg-amber-950/60 px-2.5 py-1 text-xs font-bold text-amber-600 dark:text-amber-400">
                    <Crown className="h-3.5 w-3.5" />
                    Trưởng nhóm
                  </span>
                )}
                {selectedMember.role === 'ADMIN' && (
                  <span className="inline-flex items-center gap-1 rounded-full border border-blue-200/70 dark:border-blue-800/60 bg-blue-50 dark:bg-blue-950/60 px-2.5 py-1 text-xs font-bold text-blue-600 dark:text-blue-400">
                    <Shield className="h-3.5 w-3.5" />
                    Quản trị
                  </span>
                )}
                {selectedMember.role === 'MEMBER' && (
                  <span className="inline-flex items-center rounded-full border border-slate-200/70 dark:border-slate-700 bg-slate-100 dark:bg-slate-800 px-2.5 py-1 text-xs font-semibold text-slate-600 dark:text-slate-300">
                    Thành viên
                  </span>
                )}
              </div>
            </div>

            {/* Name & Username */}
            <div>
              <h3 className="text-base font-bold leading-snug text-slate-900 dark:text-white">
                {selectedMember.displayName}
              </h3>
              <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                <span>@{selectedMember.username}</span>
              </div>
            </div>

            {/* Custom Status Box */}
            {selectedMember.customStatus && (
              <div
                className={`mt-3 flex items-center gap-2 rounded-2xl border p-2.5 text-xs ${
                  isDark
                    ? 'border-slate-800 bg-slate-900/90 text-slate-300'
                    : 'border-slate-200/80 bg-slate-50 text-slate-600'
                }`}
              >
                <Sparkles className="h-3.5 w-3.5 shrink-0 text-[#2563eb] dark:text-blue-400" />
                <span className="truncate">{selectedMember.customStatus}</span>
              </div>
            )}

            {/* Quick Actions (DM + Add Friend) if not currentUser */}
            {currentUser?.id !== selectedMember.userId && (
              <div className="mt-4 flex gap-2">
                <button
                  type="button"
                  onClick={() => handleOpenDm(selectedMember)}
                  className="flex flex-1 items-center justify-center gap-1.5 rounded-2xl bg-blue-600 px-3 py-2.5 text-xs font-bold text-white shadow-xs shadow-blue-500/25 transition hover:bg-blue-700 cursor-pointer"
                >
                  <MessageSquare className="h-3.5 w-3.5" />
                  <span>Nhắn tin riêng</span>
                </button>

                {!isAlreadyFriend(
                  selectedMember.userId,
                  selectedMember.username
                ) && (
                  <button
                    type="button"
                    disabled={sentRequestIds.includes(selectedMember.userId)}
                    onClick={() => handleAddFriend(selectedMember)}
                    className={`flex items-center justify-center gap-1 rounded-2xl border px-3 py-2.5 text-xs font-bold transition cursor-pointer ${
                      sentRequestIds.includes(selectedMember.userId)
                        ? 'border-blue-300 bg-[#eff6ff] text-blue-600 dark:border-blue-800 dark:bg-blue-950/60 dark:text-blue-400'
                        : isDark
                        ? 'border-slate-700 bg-slate-900 text-slate-100 hover:border-blue-500'
                        : 'border-slate-200 bg-slate-50 text-slate-900 hover:border-blue-500'
                    }`}
                  >
                    {sentRequestIds.includes(selectedMember.userId) ? (
                      <>
                        <Check className="h-3.5 w-3.5" />
                        <span>Đã gửi</span>
                      </>
                    ) : (
                      <>
                        <UserPlus className="h-3.5 w-3.5" />
                        <span>Kết bạn</span>
                      </>
                    )}
                  </button>
                )}
              </div>
            )}

            {/* Role Management & Kick Member (Owner / Admin) */}
            {(isCurrentUserOwner || isCurrentUserAdmin) &&
              currentUser?.id !== selectedMember.userId && (
                <div className="mt-4 border-t border-slate-200 dark:border-slate-800 pt-3 space-y-2.5">
                  {isCurrentUserOwner && (
                    <div>
                      <label className="mb-1.5 block text-[10px] font-bold tracking-wider uppercase text-slate-400 dark:text-slate-500">
                        Vai trò thành viên
                      </label>
                      <div className="grid grid-cols-3 gap-1.5">
                        {(['OWNER', 'ADMIN', 'MEMBER'] as MemberRole[]).map(
                          (roleOpt) => {
                            const active = selectedMember.role === roleOpt;
                            return (
                              <button
                                key={roleOpt}
                                type="button"
                                onClick={() =>
                                  handleRoleChange(selectedMember, roleOpt)
                                }
                                className={`rounded-lg py-1.5 text-[11px] font-bold transition cursor-pointer ${
                                  active
                                    ? 'bg-blue-600 text-white shadow-2xs'
                                    : isDark
                                    ? 'bg-slate-900 text-slate-400 hover:text-white hover:bg-slate-800'
                                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                                }`}
                              >
                                {roleOpt === 'OWNER' ? 'Chủ nhóm' : roleOpt === 'ADMIN' ? 'Quản trị' : 'Thành viên'}
                              </button>
                            );
                          }
                        )}
                      </div>
                    </div>
                  )}

                  {selectedMember.role !== 'OWNER' && (
                    <button
                      type="button"
                      disabled
                      title="Chưa thể xóa thành viên lúc này"
                      className="flex w-full items-center justify-center gap-1.5 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-100 dark:bg-slate-800 px-3 py-1.5 text-xs font-bold text-slate-500 dark:text-slate-400 cursor-not-allowed"
                    >
                      <UserMinus className="h-3.5 w-3.5" />
                      <span>Chưa thể xóa thành viên khỏi nhóm</span>
                    </button>
                  )}
                </div>
              )}
          </div>
        </div>
      )}
    </aside>
  );
};

export default MemberSidebar;
