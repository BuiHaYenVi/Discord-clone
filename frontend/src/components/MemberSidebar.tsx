import React, { useState } from 'react';
import type { User } from '../types/chat';
import { Crown, Shield, MoreVertical } from 'lucide-react';

interface Props {
  members: User[];
  currentUserId?: number;
  currentUserRole?: string;
  onDirectMessage: (userId: number, name: string) => void;
  onSetMemberRole?: (userId: number, role: string) => void;
  onTransferOwner?: (userId: number) => void;
  onKickMember?: (userId: number) => void;
}

export const MemberSidebar: React.FC<Props> = ({
  members,
  currentUserId,
  currentUserRole,
  onDirectMessage,
  onSetMemberRole,
  onTransferOwner,
  onKickMember
}) => {
  const [selectedMember, setSelectedMember] = useState<User | null>(null);

  const onlineMembers = members.filter(m => m.online !== false);
  const offlineMembers = members.filter(m => m.online === false);
  const isOwner = currentUserRole === 'OWNER';

  const handleAction = (type: 'role' | 'transfer' | 'kick', target: User) => {
    setSelectedMember(null);
    if (type === 'role') {
      const nextRole = target.role === 'ADMIN' ? 'MEMBER' : 'ADMIN';
      if (onSetMemberRole) onSetMemberRole(target.id, nextRole);
    } else if (type === 'transfer') {
      if (onTransferOwner) onTransferOwner(target.id);
    } else if (type === 'kick') {
      if (onKickMember) onKickMember(target.id);
    }
  };

  const renderMemberItem = (member: User, isOnline: boolean) => {
    const isMe = member.id === currentUserId;

    return (
      <div
        key={member.id}
        className={`member-item ${isOnline ? 'online' : 'offline'}`}
        onClick={() => !isMe && onDirectMessage(member.id, member.displayName)}
        title={isMe ? 'Bạn' : 'Nhấn để gửi tin nhắn riêng'}
        style={{ position: 'relative' }}
      >
        <div className="user-avatar-wrap">
          <div className="user-avatar-circle">
            {member.displayName ? member.displayName.charAt(0).toUpperCase() : 'U'}
          </div>
          <div className={`status-dot ${isOnline ? '' : 'offline'}`} />
        </div>

        <div style={{ flex: 1, display: 'flex', alignItems: 'center', gap: 6, overflow: 'hidden' }}>
          <span className="name">
            {member.displayName} {isMe ? '(Bạn)' : ''}
          </span>
          {member.role === 'OWNER' && (
            <span title="Chủ phòng" style={{ display: 'inline-flex' }}>
              <Crown size={14} color="var(--accent-yellow)" />
            </span>
          )}
          {member.role === 'ADMIN' && (
            <span title="Quản trị viên" style={{ display: 'inline-flex' }}>
              <Shield size={14} color="var(--accent-blurple)" />
            </span>
          )}
        </div>

        {/* Nút tùy chọn của Chủ phòng */}
        {isOwner && !isMe && (
          <button
            className="channel-settings-btn"
            title="Tùy chọn quản lý thành viên"
            onClick={(e) => {
              e.stopPropagation();
              setSelectedMember(selectedMember?.id === member.id ? null : member);
            }}
          >
            <MoreVertical size={14} />
          </button>
        )}

        {/* Dropdown quản lý thành viên */}
        {selectedMember?.id === member.id && (
          <div className="member-action-menu" onClick={e => e.stopPropagation()}>
            <button
              className="member-action-btn"
              onClick={() => handleAction('role', member)}
            >
              {member.role === 'ADMIN' ? 'Hạ xuống Thành viên' : 'Phong làm Quản trị viên'}
            </button>

            <button
              className="member-action-btn warning"
              onClick={() => handleAction('transfer', member)}
            >
              Chuyển quyền Chủ phòng
            </button>

            <button
              className="member-action-btn danger"
              onClick={() => handleAction('kick', member)}
            >
              Xóa khỏi phòng (Kick)
            </button>
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="member-sidebar">
      <div className="sidebar-simple-header">
        <span>Thành viên ({members.length})</span>
      </div>

      <div className="member-list">
        {onlineMembers.length > 0 && (
          <>
            <div className="section-title">TRỰC TUYẾN — {onlineMembers.length}</div>
            {onlineMembers.map(member => renderMemberItem(member, true))}
          </>
        )}

        {offlineMembers.length > 0 && (
          <>
            <div className="section-title" style={{ marginTop: '12px' }}>
              NGOẠI TUYẾN — {offlineMembers.length}
            </div>
            {offlineMembers.map(member => renderMemberItem(member, false))}
          </>
        )}

        {members.length === 0 && (
          <div style={{ padding: '8px', fontSize: '13px', color: 'var(--text-muted)' }}>
            Hãy vào một phòng chat để xem danh sách thành viên.
          </div>
        )}
      </div>
    </div>
  );
};
