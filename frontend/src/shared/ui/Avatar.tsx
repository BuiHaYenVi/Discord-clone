import React, { useState } from 'react';
import { Users } from 'lucide-react';
import type { PresenceStatus } from '../types/api';

export interface AvatarProps {
  name: string;
  src?: string | null;
  color?: string | null;
  size?: 'xs' | 'sm' | 'md' | 'lg' | 'xl' | '2xl';
  status?: PresenceStatus | null;
  showStatus?: boolean;
  isSpeaking?: boolean;
  isGroup?: boolean;
  className?: string;
  badge?: React.ReactNode;
  onClick?: () => void;
}

const AVATAR_GRADIENTS = [
  'from-blue-500 to-indigo-600',
  'from-sky-500 to-blue-600',
  'from-teal-500 to-emerald-600',
  'from-amber-500 to-orange-600',
  'from-rose-500 to-pink-600',
  'from-cyan-500 to-blue-500',
  'from-emerald-500 to-teal-600',
  'from-indigo-500 to-blue-700',
];

function getAvatarGradient(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return AVATAR_GRADIENTS[Math.abs(hash) % AVATAR_GRADIENTS.length];
}

function getInitials(name: string): string {
  const trimmed = (name || '?').trim();
  if (!trimmed) return '?';
  const parts = trimmed.split(/\s+/);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }
  return trimmed.slice(0, 2).toUpperCase();
}

const SIZE_CLASSES: Record<NonNullable<AvatarProps['size']>, string> = {
  xs: 'w-6 h-6 text-[10px]',
  sm: 'w-8 h-8 text-[11px]',
  md: 'w-9 h-9 text-[12px]',
  lg: 'w-10 h-10 text-[13px]',
  xl: 'w-12 h-12 text-[15px]',
  '2xl': 'w-16 h-16 text-[18px]',
};

const GROUP_ICON_SIZES: Record<NonNullable<AvatarProps['size']>, string> = {
  xs: 'w-3 h-3',
  sm: 'w-3.5 h-3.5',
  md: 'w-4 h-4',
  lg: 'w-5 h-5',
  xl: 'w-6 h-6',
  '2xl': 'w-7 h-7',
};

const STATUS_DOT_SIZES: Record<NonNullable<AvatarProps['size']>, string> = {
  xs: 'w-2 h-2 border-[1.5px]',
  sm: 'w-2.5 h-2.5 border-2',
  md: 'w-2.5 h-2.5 border-2',
  lg: 'w-3 h-3 border-2',
  xl: 'w-3.5 h-3.5 border-[2.5px]',
  '2xl': 'w-4 h-4 border-[2.5px]',
};

const STATUS_COLORS: Record<PresenceStatus, string> = {
  ONLINE: 'bg-green-500',
  IDLE: 'bg-amber-500',
  DND: 'bg-red-500',
  OFFLINE: 'bg-slate-400 dark:bg-slate-500',
};

export const Avatar: React.FC<AvatarProps> = ({
  name,
  src,
  color,
  size = 'md',
  status,
  showStatus = false,
  isSpeaking = false,
  isGroup = false,
  className = '',
  badge,
  onClick,
}) => {
  const [imgError, setImgError] = useState(false);
  const gradient = getAvatarGradient(name || 'Uri');
  const initials = getInitials(name || 'U');
  const resolvedStatus: PresenceStatus = status || 'OFFLINE';

  return (
    <div
      className={`relative inline-flex items-center justify-center shrink-0 select-none ${
        onClick ? 'cursor-pointer' : ''
      } ${className}`}
      onClick={onClick}
    >
      <div
        className={`${SIZE_CLASSES[size]} rounded-full flex items-center justify-center font-bold text-white shadow-xs overflow-hidden transition-transform duration-150 ${
          !src || imgError ? (!color ? `bg-gradient-to-br ${gradient}` : '') : 'bg-slate-100 dark:bg-slate-800'
        } ${
          isSpeaking
            ? 'ring-2 ring-emerald-500 ring-offset-2 ring-offset-white dark:ring-offset-slate-900'
            : ''
        }`}
        style={!src || imgError ? (color ? { backgroundColor: color } : undefined) : undefined}
      >
        {src && !imgError ? (
          <img
            src={src}
            alt={name}
            className="w-full h-full object-cover"
            onError={() => setImgError(true)}
            loading="lazy"
          />
        ) : isGroup ? (
          <Users className={`${GROUP_ICON_SIZES[size]} text-white/95 stroke-[2.2]`} />
        ) : (
          <span className="tracking-tight leading-none">{initials}</span>
        )}
      </div>

      {showStatus && (
        <span
          className={`absolute -bottom-0.5 -right-0.5 ${STATUS_DOT_SIZES[size]} ${STATUS_COLORS[resolvedStatus]} rounded-full border-white dark:border-slate-900 transition-colors duration-200`}
          aria-label={resolvedStatus}
        />
      )}

      {badge && <div className="absolute -top-1 -right-1">{badge}</div>}
    </div>
  );
};

export default Avatar;
