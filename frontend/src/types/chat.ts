export interface User {
  id: number;
  username: string;
  displayName: string;
  role?: string;
  online?: boolean;
}

export interface Room {
  id: number;
  name: string;
  description?: string;
  inviteCode?: string;
  role?: string;
  unreadCount?: number;
  memberCount?: number;
}

export interface Reaction {
  emoji: string;
  count: number;
  me?: boolean;
}

export interface MessageItem {
  id: number;
  senderId: number;
  senderName: string;
  avatar?: string;
  content: string;
  timestamp: string;
  date?: string;
  isMe?: boolean;
  isEdited?: boolean;
  reactions?: Reaction[];
  attachment?: {
    name: string;
    size: string;
    url?: string;
  };
}

export interface Friend {
  id: number;
  username: string;
  displayName: string;
  online: boolean;
}

export interface PendingFriendRequest {
  requestId: number;
  senderId: number;
  senderUsername: string;
  senderDisplayName: string;
  time: string;
}

export interface SearchUserResult {
  id: number;
  username: string;
  displayName: string;
  isFriend: boolean;
  online: boolean;
}
