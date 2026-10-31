import React, { useRef, useState } from 'react';
import {
  CornerUpLeft,
  Edit3,
  Pin,
  Plus,
  Trash2,
  Check,
  X,
  Copy,
} from 'lucide-react';
import type { Attachment, Message as UriMessage, PresenceStatus } from '../../shared/types/api';
import {
  Message as MessageContainer,
  MessageAvatar,
  MessageContent,
  MessageActions,
  MessageAction,
} from '../../shared/ui/message-primitives';
import { FileAttachmentCard } from './FileAttachmentCard';
import { ReactionPickerPopover } from './ReactionPickerPopover';
import { TEAMS_QUICK_REACTIONS } from './TeamsEmojiCatalog';
import { TeamsEmoji } from './TeamsEmoji';

const DEFAULT_QUICK_EMOJIS = TEAMS_QUICK_REACTIONS.map(({ emoji }) => emoji);

export interface SafeMarkdownContentProps {
  content: string;
  currentUsername?: string;
  isOwn?: boolean;
}

/**
 * Safe React-only Markdown & @mention renderer (No dangerouslySetInnerHTML / XSS-safe).
 */
export const SafeMarkdownContent: React.FC<SafeMarkdownContentProps> = ({
  content,
  currentUsername,
  isOwn = false,
}) => {
  const blocks = content.split(/(```[\s\S]*?```)/g);

  const renderInline = (text: string, keyPrefix: string) => {
    const tokens = text.split(
      /(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|~~[^~\n]+~~|https?:\/\/[^\s<]+|@[a-zA-Z0-9_.-]+)/g
    );
    return tokens.map((tok, idx) => {
      if (!tok) return null;
      const key = `${keyPrefix}-${idx}`;

      if (tok.startsWith('`') && tok.endsWith('`') && tok.length > 2) {
        return (
          <code
            key={key}
            className="px-1.5 py-0.5 mx-0.5 text-[12px] font-mono rounded-md bg-slate-200/80 dark:bg-slate-900/70 text-blue-700 dark:text-blue-300 border border-slate-300/60 dark:border-slate-700/70"
          >
            {tok.slice(1, -1)}
          </code>
        );
      }
      if (tok.startsWith('**') && tok.endsWith('**') && tok.length > 4) {
        return (
          <strong key={key} className="font-bold">
            {tok.slice(2, -2)}
          </strong>
        );
      }
      if (tok.startsWith('*') && tok.endsWith('*') && tok.length > 2) {
        return (
          <em key={key} className="italic">
            {tok.slice(1, -1)}
          </em>
        );
      }
      if (tok.startsWith('~~') && tok.endsWith('~~') && tok.length > 4) {
        return (
          <del key={key} className="line-through opacity-75">
            {tok.slice(2, -2)}
          </del>
        );
      }
      if (/^https?:\/\//.test(tok)) {
        return (
          <a
            key={key}
            href={tok}
            target="_blank"
            rel="noopener noreferrer"
            className={`underline underline-offset-2 font-semibold break-all ${
              isOwn
                ? 'text-blue-700 dark:text-blue-100 hover:text-blue-900 dark:hover:text-white'
                : 'text-blue-600 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300'
            }`}
          >
            {tok}
          </a>
        );
      }
      if (tok.startsWith('@') && tok.length > 1) {
        const mentionName = tok.slice(1);
        const isMe =
          currentUsername &&
          mentionName.toLowerCase() === currentUsername.toLowerCase();
        return (
          <span
            key={key}
            className={`inline-flex items-center px-1.5 py-0.2 rounded-md text-[12.5px] font-bold ${
              isMe
                ? 'bg-amber-400/25 text-amber-800 dark:text-amber-200 ring-1 ring-amber-400/50'
                : 'bg-blue-500/15 dark:bg-blue-400/20 text-blue-700 dark:text-blue-200'
            }`}
          >
            {tok}
          </span>
        );
      }
      return <React.Fragment key={key}>{tok}</React.Fragment>;
    });
  };

  return (
    <div className="space-y-1.5 leading-[1.5] break-words">
      {blocks.map((block, bIdx) => {
        if (block.startsWith('```') && block.endsWith('```') && block.length >= 6) {
          const raw = block.slice(3, -3).replace(/^[a-zA-Z0-9_-]+\n/, '');
          return (
            <pre
              key={`code-${bIdx}`}
              className="p-2.5 my-1 rounded-xl bg-slate-900 text-slate-100 font-mono text-[12px] overflow-x-auto border border-slate-800 shadow-inner"
            >
              <code>{raw}</code>
            </pre>
          );
        }
        return (
          <span key={`txt-${bIdx}`} className="whitespace-pre-wrap">
            {renderInline(block, `b${bIdx}`)}
          </span>
        );
      })}
    </div>
  );
};

export interface MessageItemLabels {
  edited?: string;
  deleted?: string;
  pinned?: string;
  reply?: string;
  edit?: string;
  delete?: string;
  pin?: string;
  unpin?: string;
  save?: string;
  cancel?: string;
  you?: string;
}

export interface MessageItemProps {
  message: UriMessage;
  isOwn: boolean;
  isCompact?: boolean;
  isNextSameSender?: boolean;
  senderStatus?: PresenceStatus;
  senderRoleLabel?: string | null;
  currentUserId?: number;
  currentUsername?: string;
  isHighlighted?: boolean;
  isEditing?: boolean;
  editingDraft?: string;
  onChangeEditingDraft?: (value: string) => void;
  onSaveEdit?: () => void;
  onCancelEdit?: () => void;
  onStartEdit?: (message: UriMessage) => void;
  onDelete?: (messageId: number) => void;
  onReply?: (message: UriMessage) => void;
  onTogglePin?: (messageId: number) => void;
  onToggleReaction?: (messageId: number, emoji: string) => void;
  onJumpToMessage?: (messageId: number) => void;
  onOpenImagePreview?: (url: string, fileName: string) => void;
  canPin?: boolean;
  canDelete?: boolean;
  resolveAttachmentUrl: (url: string) => string;
  isVoiceAttachment?: (att: Attachment) => boolean;
  renderVoiceAttachment?: (att: Attachment, message: UriMessage) => React.ReactNode;
  renderCustomContent?: (content: string, message: UriMessage) => React.ReactNode | null;
  quickEmojis?: string[];
  labels?: MessageItemLabels;
}

function formatMessageTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return '';
  }
}

export const MessageItem: React.FC<MessageItemProps> = ({
  message,
  isOwn,
  isCompact = false,
  isNextSameSender = false,
  senderStatus,
  senderRoleLabel,
  currentUserId,
  currentUsername,
  isHighlighted = false,
  isEditing = false,
  editingDraft = '',
  onChangeEditingDraft,
  onSaveEdit,
  onCancelEdit,
  onStartEdit,
  onDelete,
  onReply,
  onTogglePin,
  onToggleReaction,
  onJumpToMessage,
  onOpenImagePreview,
  canPin = false,
  canDelete = false,
  resolveAttachmentUrl,
  isVoiceAttachment,
  renderVoiceAttachment,
  renderCustomContent,
  quickEmojis = DEFAULT_QUICK_EMOJIS,
  labels,
}) => {
  const [showEmojiPicker, setShowEmojiPicker] = useState(false);
  const [copied, setCopied] = useState(false);
  const reactionPickerTriggerRef = useRef<HTMLButtonElement>(null);

  const isDeleted = Boolean(message.deletedAt);
  const timeLabel = formatMessageTime(message.createdAt);

  // Corner grouping logic matching FE-ZOHO MessageBubble
  const cornerClasses = isOwn
    ? `${isCompact ? 'rounded-tr-[5px]' : ''} ${isNextSameSender ? 'rounded-br-[5px]' : 'rounded-br-[5px]'}`
    : `${isCompact ? 'rounded-tl-[5px]' : ''} ${isNextSameSender ? 'rounded-bl-[5px]' : 'rounded-bl-[5px]'}`;

  const customRendered =
    !isDeleted && renderCustomContent
      ? renderCustomContent(message.content, message)
      : null;

  const handleCopyText = () => {
    if (!message.content) return;
    navigator.clipboard?.writeText(message.content).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 1400);
  };

  return (
    <div
      id={`msg-${message.id}`}
      className={`group relative flex flex-col px-3 sm:px-5 transition-colors duration-200 ${
        isCompact ? 'mt-0.5' : 'mt-3.5'
      } ${
        isHighlighted
          ? 'bg-blue-500/10 dark:bg-blue-500/15 rounded-2xl py-1.5 ring-1 ring-blue-400/40'
          : 'hover:bg-slate-100/55 dark:hover:bg-slate-800/35 rounded-2xl py-0.5'
      }`}
    >
      <MessageContainer
        className={`items-end ${isOwn ? 'flex-row-reverse' : 'flex-row'} max-w-full`}
      >
        {/* Sender Avatar or Compact Spacer */}
        {!isOwn && (
          <div className="w-8 shrink-0 flex flex-col items-center">
            {!isCompact ? (
              <MessageAvatar
                alt={message.senderName}
                color={message.senderAvatarColor}
                status={senderStatus}
                showStatus={Boolean(senderStatus)}
              />
            ) : (
              <span className="text-[9.5px] font-semibold text-slate-400 dark:text-slate-500 opacity-0 group-hover:opacity-100 transition-opacity tabular-nums select-none leading-6">
                {timeLabel}
              </span>
            )}
          </div>
        )}

        {/* Message Column */}
        <div
          className={`flex flex-col min-w-0 max-w-[78%] sm:max-w-[72%] ${
            isOwn ? 'items-end' : 'items-start'
          }`}
        >
          {/* Sender Header (only on first message in group) */}
          {!isCompact && (
            <div
              className={`flex items-center gap-2 mb-1 px-1 ${
                isOwn ? 'flex-row-reverse' : 'flex-row'
              }`}
            >
              <span className="text-[12.5px] font-bold text-slate-700 dark:text-slate-200 tracking-tight">
                {isOwn ? labels?.you || message.senderName : message.senderName}
              </span>
              {senderRoleLabel && !isOwn && (
                <span className="px-1.5 py-0.2 rounded-md text-[9.5px] font-extrabold uppercase tracking-wider bg-blue-50 dark:bg-blue-950/70 text-blue-600 dark:text-blue-400 border border-blue-200/70 dark:border-blue-800/60">
                  {senderRoleLabel}
                </span>
              )}
              <span className="text-[10.5px] font-semibold text-slate-400 dark:text-slate-500 tabular-nums">
                {timeLabel}
              </span>
              {message.pinned && (
                <span className="inline-flex items-center gap-0.5 text-[10px] font-bold text-amber-600 dark:text-amber-400">
                  <Pin className="w-2.5 h-2.5 rotate-45 fill-current" />
                  {labels?.pinned || 'Pinned'}
                </span>
              )}
            </div>
          )}

          {/* Reply Context Banner */}
          {message.replyTo && (
            <button
              type="button"
              onClick={() => onJumpToMessage?.(message.replyTo!.messageId)}
              className={`mb-1 flex items-center gap-1.5 px-2.5 py-1 rounded-xl text-[11.5px] border transition-colors max-w-full truncate cursor-pointer ${
                isOwn
                  ? 'bg-blue-50/90 dark:bg-blue-950/50 border-blue-200/80 dark:border-blue-800/60 text-blue-900 dark:text-blue-200'
                  : 'bg-slate-100/90 dark:bg-slate-800/70 border-slate-200/80 dark:border-slate-700/60 text-slate-600 dark:text-slate-300'
              }`}
            >
              <CornerUpLeft className="w-3 h-3 text-blue-500 shrink-0" />
              <span className="font-bold shrink-0">{message.replyTo.senderName}:</span>
              <span className="truncate opacity-85">{message.replyTo.content}</span>
            </button>
          )}

          {/* Inline Editing Mode */}
          {isEditing ? (
            <div className="w-full min-w-[260px] sm:min-w-[340px] p-2.5 rounded-2xl bg-white dark:bg-slate-900 border-2 border-blue-500 shadow-md space-y-2">
              <textarea
                value={editingDraft}
                onChange={(e) => onChangeEditingDraft?.(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    onSaveEdit?.();
                  } else if (e.key === 'Escape') {
                    onCancelEdit?.();
                  }
                }}
                rows={2}
                autoFocus
                className="w-full text-[13.5px] bg-transparent text-slate-900 dark:text-slate-100 focus:outline-none resize-none"
              />
              <div className="flex items-center justify-end gap-1.5 text-xs">
                <button
                  type="button"
                  onClick={onCancelEdit}
                  className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg font-semibold text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors cursor-pointer"
                >
                  <X className="w-3.5 h-3.5" />
                  {labels?.cancel || 'Cancel'}
                </button>
                <button
                  type="button"
                  onClick={onSaveEdit}
                  className="inline-flex items-center gap-1 px-3 py-1 rounded-lg font-bold bg-blue-600 hover:bg-blue-700 text-white shadow-2xs transition-colors cursor-pointer"
                >
                  <Check className="w-3.5 h-3.5" />
                  {labels?.save || 'Save'}
                </button>
              </div>
            </div>
          ) : isDeleted ? (
            <MessageContent
              variant="default"
              size="sm"
              className="italic text-slate-400 dark:text-slate-500 bg-slate-100/70 dark:bg-slate-800/45 border-dashed"
            >
              {labels?.deleted || 'This message was deleted.'}
            </MessageContent>
          ) : customRendered ? (
            <div className="max-w-full">{customRendered}</div>
          ) : (
            message.content && (
              <MessageContent
                variant={isOwn ? 'primary' : 'default'}
                size="md"
                className={cornerClasses}
              >
                <SafeMarkdownContent
                  content={message.content}
                  currentUsername={currentUsername}
                  isOwn={isOwn}
                />
                {message.editedAt && (
                  <span
                    className={`inline-block ml-1.5 text-[10px] font-semibold select-none ${
                      isOwn
                        ? 'text-blue-700/75 dark:text-blue-200/75'
                        : 'text-slate-400 dark:text-slate-400'
                    }`}
                  >
                    ({labels?.edited || 'edited'})
                  </span>
                )}
              </MessageContent>
            )
          )}

          {/* Attachments List */}
          {!isDeleted && message.attachments && message.attachments.length > 0 && (
            <div
              className={`flex flex-wrap gap-2 ${
                message.content ? 'mt-1.5' : ''
              } ${isOwn ? 'justify-end' : 'justify-start'}`}
            >
              {message.attachments.map((att) => {
                if (isVoiceAttachment?.(att) && renderVoiceAttachment) {
                  return (
                    <React.Fragment key={att.id}>
                      {renderVoiceAttachment(att, message)}
                    </React.Fragment>
                  );
                }
                const resolvedUrl = resolveAttachmentUrl(att.url);
                return (
                  <FileAttachmentCard
                    key={att.id}
                    attachment={att}
                    resolvedUrl={resolvedUrl}
                    isOwn={isOwn}
                    onPreviewImage={onOpenImagePreview}
                  />
                );
              })}
            </div>
          )}

          {/* Reactions Strip */}
          {!isDeleted && message.reactions && message.reactions.length > 0 && (
            <div
              className={`flex flex-wrap items-center gap-1 mt-1 ${
                isOwn ? 'justify-end' : 'justify-start'
              }`}
            >
              {message.reactions.map((rg) => {
                const reactedByMe =
                  currentUserId !== undefined && rg.userIds.includes(currentUserId);
                return (
                  <button
                    key={rg.emoji}
                    type="button"
                    onClick={() => onToggleReaction?.(message.id, rg.emoji)}
                    className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11.5px] font-bold border transition-all cursor-pointer ${
                      reactedByMe
                        ? 'bg-blue-50 dark:bg-blue-950/70 border-blue-300 dark:border-blue-500/60 text-blue-700 dark:text-blue-300 shadow-2xs'
                        : 'bg-white/90 dark:bg-slate-800/90 border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:border-slate-300'
                    }`}
                  >
                    <TeamsEmoji emoji={rg.emoji} size={18} />
                    <span className="tabular-nums text-[10.5px]">{rg.userIds.length}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      </MessageContainer>

      {/* Cliq Floating Hover Action Pill */}
      {!isDeleted && !isEditing && (
        <div
          className={`absolute -top-3 ${
            isOwn ? 'left-4' : 'right-4'
          } opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity duration-150 z-20`}
        >
          <MessageActions className="bg-white dark:bg-slate-800 border border-slate-200/90 dark:border-slate-700 shadow-md rounded-xl px-1.5 py-0.5">
            {onToggleReaction && (
              <div className="flex items-center gap-0.5">
                {quickEmojis.map((emoji) => (
                  <MessageAction key={emoji} tooltip={emoji}>
                    <button
                      type="button"
                      aria-label={`Thả cảm xúc ${emoji}`}
                      onClick={() => onToggleReaction(message.id, emoji)}
                      className="flex h-7 w-7 items-center justify-center rounded-lg transition-transform hover:scale-110 hover:bg-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 dark:hover:bg-slate-700/70"
                    >
                      <TeamsEmoji emoji={emoji} size={20} />
                    </button>
                  </MessageAction>
                ))}
                <div className="relative">
                  <MessageAction tooltip="Thêm cảm xúc">
                    <button
                      ref={reactionPickerTriggerRef}
                      type="button"
                      aria-label="Chọn cảm xúc khác"
                      aria-expanded={showEmojiPicker}
                      onClick={() => setShowEmojiPicker((previous) => !previous)}
                      className="flex h-7 w-7 items-center justify-center rounded-lg text-slate-500 transition-colors hover:bg-slate-100 hover:text-blue-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 dark:text-slate-400 dark:hover:bg-slate-700/70 dark:hover:text-blue-400"
                    >
                      <Plus className="h-4 w-4" />
                    </button>
                  </MessageAction>
                  <ReactionPickerPopover
                    isOpen={showEmojiPicker}
                    triggerRef={reactionPickerTriggerRef}
                    onClose={() => setShowEmojiPicker(false)}
                    onSelectEmoji={(emoji) => onToggleReaction(message.id, emoji)}
                    placement="below"
                    className={isOwn ? 'left-0' : 'right-0'}
                  />
                </div>
              </div>
            )}

            {onReply && (
              <MessageAction tooltip={labels?.reply || 'Reply'}>
                <button
                  type="button"
                  onClick={() => onReply(message)}
                  className="p-1.5 rounded-lg text-slate-500 hover:text-blue-600 dark:text-slate-400 dark:hover:text-blue-400 hover:bg-slate-100 dark:hover:bg-slate-700/70 transition-colors cursor-pointer"
                >
                  <CornerUpLeft className="w-3.5 h-3.5" />
                </button>
              </MessageAction>
            )}

            {message.content && (
              <MessageAction tooltip={copied ? 'Copied!' : 'Copy text'}>
                <button
                  type="button"
                  onClick={handleCopyText}
                  className="p-1.5 rounded-lg text-slate-500 hover:text-blue-600 dark:text-slate-400 dark:hover:text-blue-400 hover:bg-slate-100 dark:hover:bg-slate-700/70 transition-colors cursor-pointer"
                >
                  {copied ? (
                    <Check className="w-3.5 h-3.5 text-emerald-500" />
                  ) : (
                    <Copy className="w-3.5 h-3.5" />
                  )}
                </button>
              </MessageAction>
            )}

            {canPin && onTogglePin && (
              <MessageAction
                tooltip={
                  message.pinned ? labels?.unpin || 'Unpin' : labels?.pin || 'Pin'
                }
              >
                <button
                  type="button"
                  onClick={() => onTogglePin(message.id)}
                  className={`p-1.5 rounded-lg transition-colors cursor-pointer ${
                    message.pinned
                      ? 'text-amber-500 bg-amber-50 dark:bg-amber-950/50'
                      : 'text-slate-500 hover:text-amber-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700/70'
                  }`}
                >
                  <Pin className="w-3.5 h-3.5" />
                </button>
              </MessageAction>
            )}

            {isOwn && onStartEdit && (
              <MessageAction tooltip={labels?.edit || 'Edit'}>
                <button
                  type="button"
                  onClick={() => onStartEdit(message)}
                  className="p-1.5 rounded-lg text-slate-500 hover:text-blue-600 dark:text-slate-400 dark:hover:text-blue-400 hover:bg-slate-100 dark:hover:bg-slate-700/70 transition-colors cursor-pointer"
                >
                  <Edit3 className="w-3.5 h-3.5" />
                </button>
              </MessageAction>
            )}

            {(isOwn || canDelete) && onDelete && (
              <MessageAction tooltip={labels?.delete || 'Delete'}>
                <button
                  type="button"
                  onClick={() => onDelete(message.id)}
                  className="p-1.5 rounded-lg text-slate-500 hover:text-red-500 dark:text-slate-400 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40 transition-colors cursor-pointer"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </MessageAction>
            )}
          </MessageActions>
        </div>
      )}
    </div>
  );
};

export default MessageItem;
