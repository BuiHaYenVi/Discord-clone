import React from 'react';
import { Avatar } from './Avatar';
import type { PresenceStatus } from '../types/api';

function cx(...classes: Array<string | undefined | null | false>): string {
  return classes.filter(Boolean).join(' ');
}

export type MessageProps = {
  children: React.ReactNode;
  className?: string;
} & React.HTMLAttributes<HTMLDivElement>;

export const Message = ({ children, className, ...props }: MessageProps) => (
  <div className={cx('flex gap-2.5', className)} {...props}>
    {children}
  </div>
);

export type MessageAvatarProps = {
  src?: string;
  alt: string;
  fallback?: string;
  color?: string;
  status?: PresenceStatus;
  showStatus?: boolean;
  className?: string;
};

export const MessageAvatar = ({
  src,
  alt,
  color,
  status,
  showStatus = false,
  className,
}: MessageAvatarProps) => {
  return (
    <Avatar
      name={alt}
      src={src}
      color={color}
      status={status}
      showStatus={showStatus}
      size="sm"
      className={cx('h-8 w-8 shrink-0', className)}
    />
  );
};

export type MessageContentSize = 'sm' | 'md' | 'lg';
export type MessageContentVariant = 'default' | 'primary' | 'ghost';

export type MessageContentProps = {
  children: React.ReactNode;
  variant?: MessageContentVariant;
  size?: MessageContentSize;
  className?: string;
} & Omit<React.HTMLAttributes<HTMLDivElement>, 'size'>;

const VARIANT_CLASSES: Record<MessageContentVariant, string> = {
  default:
    'bg-[#F1F5F9] dark:bg-slate-800/95 text-slate-900 dark:text-slate-100 border border-slate-200/70 dark:border-slate-700/60 shadow-[0_1px_2px_rgba(15,23,42,0.03)]',
  primary:
    'bg-[#DBEAFE] dark:bg-[#1D4ED8] text-slate-900 dark:text-white border border-[#BFDBFE] dark:border-blue-600/80 shadow-[0_1px_2px_rgba(37,99,235,0.06)]',
  ghost: 'bg-transparent text-slate-900 dark:text-slate-100 p-0',
};

const SIZE_CLASSES: Record<MessageContentSize, string> = {
  sm: 'px-3 py-1.5 text-[13px] leading-[1.45]',
  md: 'px-3.5 py-2 text-[13.5px] leading-[1.5]',
  lg: 'px-4 py-2.5 text-[14px] leading-[1.55]',
};

export const MessageContent = ({
  children,
  variant = 'default',
  size = 'md',
  className,
  ...props
}: MessageContentProps) => {
  return (
    <div
      className={cx(
        'rounded-[20px] break-words whitespace-pre-wrap transition-all duration-150',
        VARIANT_CLASSES[variant],
        variant !== 'ghost' && SIZE_CLASSES[size],
        className
      )}
      {...props}
    >
      {children}
    </div>
  );
};

export type MessageActionsProps = {
  children: React.ReactNode;
  className?: string;
} & React.HTMLAttributes<HTMLDivElement>;

export const MessageActions = ({
  children,
  className,
  ...props
}: MessageActionsProps) => (
  <div
    className={cx(
      'text-slate-500 dark:text-slate-400 flex items-center gap-0.5',
      className
    )}
    {...props}
  >
    {children}
  </div>
);

export type MessageActionProps = {
  className?: string;
  tooltip?: React.ReactNode;
  children: React.ReactNode;
} & React.ComponentProps<'div'>;

export const MessageAction = ({
  tooltip,
  children,
  className,
  ...props
}: MessageActionProps) => {
  const titleText = typeof tooltip === 'string' ? tooltip : undefined;
  return (
    <div className={cx('inline-flex items-center', className)} title={titleText} {...props}>
      {children}
    </div>
  );
};
