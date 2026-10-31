import React, { useState, useRef } from 'react';
import type { Room, MessageItem, User } from '../types/chat';
import {
  Hash, Search, UserPlus, LogOut, Send, Edit3, Trash2,
  Users, ArrowRight, Crown, Shield
} from 'lucide-react';
import { Logo } from './Logo';

interface Props {
  currentRoom: Room | null;
  messages: MessageItem[];
  members?: User[];
  currentUserId: number;
  currentUserRole?: string;
  isMemberListOpen?: boolean;
  onToggleMemberList?: () => void;
  onSendMessage: (text: string) => void;
  onEditMessage?: (messageId: number, content: string) => void;
  onDeleteMessage?: (messageId: number) => void;
  onLoadMore?: (beforeId: number) => void;
  onInvite: () => void;
  onLeave: () => void;
  onKick: () => void;
  isOwner: boolean;
}

// 1. Định dạng giờ tin nhắn dạng HH:mm, di chuột vào hiện đầy đủ ngày giờ giây
function formatMessageTime(rawTime: string): { short: string; full: string } {
  if (!rawTime) return { short: 'Vừa xong', full: '' };
  const normalized = rawTime.includes('T') ? rawTime : rawTime.replace(' ', 'T');
  const d = new Date(normalized);

  if (isNaN(d.getTime())) {
    const match = rawTime.match(/(\d{2}:\d{2})/);
    return { short: match ? match[1] : rawTime, full: rawTime };
  }

  const hours = String(d.getHours()).padStart(2, '0');
  const minutes = String(d.getMinutes()).padStart(2, '0');
  const seconds = String(d.getSeconds()).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const year = d.getFullYear();

  return {
    short: `${hours}:${minutes}`,
    full: `${hours}:${minutes}:${seconds} ngày ${day}/${month}/${year}`
  };
}

// 2. Lấy khóa ngày YYYY-MM-DD để xác định thời điểm đổi ngày
function getDateKey(rawTime: string): string {
  if (!rawTime) return '';
  const normalized = rawTime.includes('T') ? rawTime : rawTime.replace(' ', 'T');
  const d = new Date(normalized);
  if (isNaN(d.getTime())) {
    const m = rawTime.match(/^\d{4}-\d{2}-\d{2}/);
    return m ? m[0] : '';
  }
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

// 3. Hiển thị nhãn ngăn cách ngày: Hôm nay / Hôm qua / 28 tháng 6, 2026
function formatDateLabel(dateKey: string): string {
  if (!dateKey) return '';
  const parts = dateKey.split('-').map(Number);
  if (parts.length < 3) return dateKey;
  const [y, m, d] = parts;
  const msgDate = new Date(y, m - 1, d);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);

  if (msgDate.getTime() === today.getTime()) {
    return 'Hôm nay';
  }
  if (msgDate.getTime() === yesterday.getTime()) {
    return 'Hôm qua';
  }
  return `${d} tháng ${m}, ${y}`;
}

// 4. Trích xuất tên người dùng từ tin nhắn hệ thống tham gia
function extractJoinedUser(content: string): string {
  const m1 = content.match(/🔔\s*(.+?)\s*đã tham gia/i);
  if (m1) return m1[1].trim();
  const m2 = content.match(/^(.+?)\s*vừa mới xuất hiện/i);
  if (m2) return m2[1].trim();
  return '';
}

// 5. Kiểm tra gom tin nhắn liên tiếp của cùng người trong vòng 5 phút
function shouldGroupWithPrevious(
  current: MessageItem,
  previous: MessageItem | undefined,
  curDateKey: string,
  prevDateKey: string
): boolean {
  if (!previous) return false;
  if (current.senderId !== previous.senderId) return false;
  if (current.senderId === -1 || previous.senderId === -1) return false;
  if (curDateKey !== prevDateKey) return false;

  const tCur = new Date(current.timestamp?.replace(' ', 'T') || '').getTime();
  const tPrev = new Date(previous.timestamp?.replace(' ', 'T') || '').getTime();
  if (!isNaN(tCur) && !isNaN(tPrev)) {
    const diffMs = Math.abs(tCur - tPrev);
    return diffMs <= 5 * 60 * 1000;
  }
  return false;
}

export const ChatCenter: React.FC<Props> = ({
  currentRoom,
  messages,
  members = [],
  currentUserId,
  currentUserRole,
  isMemberListOpen = true,
  onToggleMemberList,
  onSendMessage,
  onEditMessage,
  onDeleteMessage,
  onLoadMore,
  onInvite,
  onLeave,
  onKick,
  isOwner
}) => {
  const [inputText, setInputText] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editText, setEditText] = useState('');
  const chatMessagesRef = useRef<HTMLDivElement>(null);

  const displayedMessages = searchTerm.trim()
    ? messages.filter(m =>
        m.content.toLowerCase().includes(searchTerm.toLowerCase()) ||
        m.senderName.toLowerCase().includes(searchTerm.toLowerCase())
      )
    : messages;

  const handleSend = () => {
    if (!inputText.trim()) return;
    onSendMessage(inputText.trim());
    setInputText('');
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      handleSend();
    }
  };

  const handleLeaveClick = () => {
    onLeave();
  };

  const handleKickClick = () => {
    onKick();
  };

  const handleSaveEdit = (msgId: number) => {
    if (!editText.trim()) return;
    if (onEditMessage) {
      onEditMessage(msgId, editText.trim());
    }
    setEditingId(null);
  };

  const handleDelete = (msgId: number) => {
    if (onDeleteMessage) {
      onDeleteMessage(msgId);
    }
  };

  const handleScroll = () => {
    if (chatMessagesRef.current && chatMessagesRef.current.scrollTop === 0) {
      if (messages.length > 0 && onLoadMore) {
        onLoadMore(messages[0].id);
      }
    }
  };

  const scrollToMessage = (msgId: number) => {
    const el = document.getElementById(`msg-${msgId}`);
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      el.classList.add('flash-highlight');
      setTimeout(() => el.classList.remove('flash-highlight'), 2000);
    }
  };

  return (
    <div className="chat-center">
      {/* 1. Header Bar chuẩn Discord */}
      <div className="chat-header">
        <div className="chat-header-left">
          <Hash size={24} className="channel-hash-icon" />
          <div className="chat-header-title">{currentRoom ? currentRoom.name : 'Chọn phòng chat'}</div>
          {currentRoom && (
            <div className="chat-header-desc">
              {currentRoom.description || 'Đây là kênh trò chuyện chính thức.'}
            </div>
          )}
        </div>

        <div className="chat-header-right">
          {/* Hộp tìm kiếm bo cong tròn chuẩn Discord */}
          <div className="discord-search-box">
            <input
              placeholder={`Tìm kiếm ${currentRoom ? currentRoom.name : ''}`}
              value={searchTerm}
              onChange={e => setSearchTerm(e.target.value)}
            />
            {searchTerm ? (
              <button
                className="search-clear-btn"
                onClick={() => setSearchTerm('')}
              >
                ✕
              </button>
            ) : (
              <Search size={15} className="search-icon" />
            )}
          </div>

          {onToggleMemberList && (
            <button
              className={`header-icon-btn ${isMemberListOpen ? 'active' : ''}`}
              title={isMemberListOpen ? 'Ẩn danh sách thành viên' : 'Hiện danh sách thành viên'}
              onClick={onToggleMemberList}
            >
              <Users size={18} />
            </button>
          )}

          {currentRoom && (
            <>
              <button className="header-btn blurple" onClick={onInvite} title="Mời bạn vào phòng">
                <UserPlus size={15} />
                <span>Mời bạn</span>
              </button>

              {isOwner && (
                <button className="header-btn red" onClick={handleKickClick} title="Kick thành viên (chỉ Chủ phòng)">
                  <span>Kick</span>
                </button>
              )}

              <button className="header-btn red" onClick={handleLeaveClick} title="Rời phòng">
                <LogOut size={15} />
                <span>Rời phòng</span>
              </button>
            </>
          )}
        </div>
      </div>

      {/* 2. Messages History */}
      <div className="chat-messages" ref={chatMessagesRef} onScroll={handleScroll}>
        {currentRoom ? (
          <>
            {onLoadMore && messages.length >= 25 && (
              <div style={{ textAlign: 'center', padding: '8px 0', fontSize: '12px', color: 'var(--text-muted)' }}>
                Cuộn lên đầu để tải thêm tin nhắn cũ hơn...
              </div>
            )}

            {/* HERO WELCOME BANNER ở đầu kênh (Chuẩn ảnh Discord thật) */}
            <div className="channel-hero-welcome">
              <div className="hero-hash-circle">
                <Hash size={40} />
              </div>
              <h1 className="hero-title">
                Chào mừng đến với #{currentRoom.name}!
              </h1>
              <p className="hero-subtitle">
                Đây là sự khởi đầu của kênh #{currentRoom.name}. Hãy gửi tin nhắn đầu tiên!
              </p>
            </div>

            {displayedMessages.map((msg, idx) => {
              const prevMsg = idx > 0 ? displayedMessages[idx - 1] : undefined;
              const curDateKey = getDateKey(msg.timestamp);
              const prevDateKey = prevMsg ? getDateKey(prevMsg.timestamp) : '';
              const isNewDay = idx === 0 || (curDateKey && curDateKey !== prevDateKey);
              const isGrouped = !isNewDay && shouldGroupWithPrevious(msg, prevMsg, curDateKey, prevDateKey);
              const timeInfo = formatMessageTime(msg.timestamp);

              // Tin nhắn hệ thống dạng Discord thật
              if (msg.senderId === -1 || msg.content.startsWith('🔔') || msg.senderName === 'Hệ thống') {
                const joinedName = extractJoinedUser(msg.content);
                const displayContent = joinedName
                  ? `${joinedName} vừa mới xuất hiện!`
                  : msg.content.replace(/^🔔\s*/, '');

                return (
                  <React.Fragment key={msg.id || idx}>
                    {isNewDay && curDateKey && (
                      <div className="date-divider">
                        <span className="date-badge">{formatDateLabel(curDateKey)}</span>
                      </div>
                    )}
                    <div className="discord-system-message-row" id={`msg-${msg.id}`}>
                      <div className="system-msg-main">
                        <ArrowRight size={16} className="system-arrow-icon" />
                        <span className="system-text">
                          <strong className="joined-username">{joinedName || displayContent}</strong>{' '}
                          {joinedName ? 'vừa mới xuất hiện!' : ''}{' '}
                          <span className="system-time">{timeInfo.short}</span>
                        </span>
                      </div>
                    </div>
                  </React.Fragment>
                );
              }

              // Thông tin vai trò của thành viên gửi tin
              const senderMember = members.find(m => m.id === msg.senderId);
              const senderRole = senderMember?.role;
              const canEdit = msg.senderId === currentUserId;
              const canDelete = msg.senderId === currentUserId || isOwner || currentUserRole === 'ADMIN';

              return (
                <React.Fragment key={msg.id || idx}>
                  {/* Ngăn cách ngày mỗi khi đổi ngày (Hôm nay / Hôm qua / dd/mm/yyyy) */}
                  {isNewDay && curDateKey && (
                    <div className="date-divider">
                      <span className="date-badge">{formatDateLabel(curDateKey)}</span>
                    </div>
                  )}

                  {/* Khung tin nhắn */}
                  <div
                    id={`msg-${msg.id}`}
                    className={`message-card ${isGrouped ? 'compact' : ''} ${msg.senderId === currentUserId ? 'highlight' : ''}`}
                    onClick={() => searchTerm && scrollToMessage(msg.id)}
                  >
                    {/* Thanh thao tác nhanh (Sửa / Xóa) khi di chuột */}
                    <div className="message-actions-toolbar">
                      {canEdit && (
                        <button
                          className="message-action-btn"
                          title="Sửa tin nhắn"
                          onClick={(e) => {
                            e.stopPropagation();
                            setEditingId(msg.id);
                            setEditText(msg.content);
                          }}
                        >
                          <Edit3 size={14} />
                        </button>
                      )}
                      {canDelete && (
                        <button
                          className="message-action-btn delete"
                          title="Xóa tin nhắn"
                          onClick={(e) => {
                            e.stopPropagation();
                            handleDelete(msg.id);
                          }}
                        >
                          <Trash2 size={14} />
                        </button>
                      )}
                    </div>

                    {isGrouped ? (
                      <div className="compact-gutter">
                        <span className="compact-time" title={timeInfo.full}>
                          {timeInfo.short}
                        </span>
                      </div>
                    ) : (
                      <div className="message-avatar">
                        {msg.senderName ? msg.senderName.charAt(0).toUpperCase() : 'U'}
                      </div>
                    )}

                    <div className="message-body">
                      {!isGrouped && (
                        <div className="message-header">
                          <span className={`sender-name ${msg.senderId === currentUserId ? 'me' : ''}`}>
                            {msg.senderName} {msg.senderId === currentUserId ? '(Bạn)' : ''}
                          </span>

                          {/* Huy hiệu vai trò */}
                          {senderRole === 'OWNER' && (
                            <span className="role-badge owner" title="Chủ phòng">
                              <Crown size={12} /> Chủ phòng
                            </span>
                          )}
                          {senderRole === 'ADMIN' && (
                            <span className="role-badge admin" title="Quản trị viên">
                              <Shield size={12} /> Admin
                            </span>
                          )}

                          <span className="message-time" title={timeInfo.full}>
                            {timeInfo.short}
                          </span>

                          {msg.isEdited && (
                            <span className="edited-indicator">(đã chỉnh sửa)</span>
                          )}
                        </div>
                      )}

                      {/* Chế độ chỉnh sửa inline */}
                      {editingId === msg.id ? (
                        <div className="inline-edit-box">
                          <input
                            className="inline-edit-input"
                            value={editText}
                            onChange={e => setEditText(e.target.value)}
                            onKeyDown={e => {
                              if (e.key === 'Enter') handleSaveEdit(msg.id);
                              if (e.key === 'Escape') setEditingId(null);
                            }}
                            autoFocus
                          />
                          <div className="inline-edit-actions">
                            <span>nhấn enter để <span className="save-btn" onClick={() => handleSaveEdit(msg.id)}>lưu</span> • nhấn esc hoặc <span className="cancel-btn" onClick={() => setEditingId(null)}>hủy</span></span>
                          </div>
                        </div>
                      ) : (
                        <div className="message-content">
                          {msg.content}
                          {isGrouped && msg.isEdited && (
                            <span className="edited-indicator">(đã chỉnh sửa)</span>
                          )}
                        </div>
                      )}

                      {/* Phản ứng reaction */}
                      {msg.reactions && msg.reactions.length > 0 && (
                        <div className="reactions-list">
                          {msg.reactions.map((r, rIdx) => (
                            <div key={rIdx} className={`reaction-pill ${r.me ? 'me' : ''}`}>
                              <span>{r.emoji}</span>
                              <span>{r.count}</span>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                </React.Fragment>
              );
            })}
          </>
        ) : (
          /* Màn hình chào khi chưa chọn phòng chat (Empty state chuẩn không dùng text kỹ thuật) */
          <div className="chat-empty-state">
            <div className="empty-state-logo-wrap">
              <Logo size={64} />
            </div>
            <h2 className="empty-state-title">Chào mừng bạn đến với LTMang Chat</h2>
            <p className="empty-state-desc">
              Hãy chọn một phòng chat ở danh sách bên trái để bắt đầu trò chuyện, hoặc nhấn nút bên dưới để tạo phòng mới kết nối cùng mọi người.
            </p>
            <button className="empty-state-action" onClick={onInvite}>
              <UserPlus size={16} />
              <span>Bắt đầu trò chuyện</span>
            </button>
          </div>
        )}
      </div>

      {/* 3. Bottom Input Box (Rộng rãi, ổn định, không icon rác) */}
      <div className="chat-input-container">
        <div className="chat-input-pill">
          <input
            placeholder={currentRoom ? `Nhắn #${currentRoom.name}` : 'Hãy chọn phòng để gõ tin nhắn...'}
            disabled={!currentRoom}
            value={inputText}
            onChange={e => setInputText(e.target.value)}
            onKeyDown={handleKeyDown}
          />

          <button
            className="chat-send-btn"
            title="Gửi tin nhắn (Enter)"
            onClick={handleSend}
            disabled={!inputText.trim() || !currentRoom}
          >
            <Send size={18} />
          </button>
        </div>
      </div>
    </div>
  );
};
