import React, { useState } from 'react';
import { Pin, ChevronDown, ChevronUp, X } from 'lucide-react';
import type { Message } from '../../shared/types/api';

export interface PinnedMessageBarProps {
  pinnedMessages: Message[];
  onJumpToMessage?: (messageId: number) => void;
  onUnpin?: (messageId: number) => void;
  canUnpin?: boolean;
  pinnedLabel?: string;
  unpinTitle?: string;
}

function getMessagePreviewText(msg: Message): string {
  const trimmed = (msg.content || '').trim();
  if (trimmed.startsWith('[URI_METRIC_V1]')) {
    return 'Tóm tắt hoạt động';
  }
  if (trimmed) return trimmed;
  if (msg.attachments && msg.attachments.length > 0) {
    return `📎 ${msg.attachments[0].filename}`;
  }
  return 'Pinned message';
}

export const PinnedMessageBar: React.FC<PinnedMessageBarProps> = ({
  pinnedMessages,
  onJumpToMessage,
  onUnpin,
  canUnpin = false,
  pinnedLabel = 'Pinned',
  unpinTitle = 'Unpin message',
}) => {
  const [activeIndex, setActiveIndex] = useState(0);
  const [expanded, setExpanded] = useState(false);

  if (!pinnedMessages || pinnedMessages.length === 0) return null;

  const safeIndex = Math.min(activeIndex, pinnedMessages.length - 1);
  const current = pinnedMessages[safeIndex] || pinnedMessages[0];

  const handleCycle = () => {
    const nextIdx = (safeIndex + 1) % pinnedMessages.length;
    setActiveIndex(nextIdx);
    onJumpToMessage?.(pinnedMessages[nextIdx].id);
  };

  return (
    <div className="border-b border-slate-200/80 dark:border-slate-800/80 bg-blue-50/55 dark:bg-slate-800/60 backdrop-blur-xs transition-all z-10 shrink-0">
      {/* Compact bar */}
      <div className="flex items-center gap-2.5 px-4 py-2 text-xs">
        <div className="flex items-center gap-1 text-blue-600 dark:text-blue-400 shrink-0">
          <Pin className="w-3.5 h-3.5 rotate-45 fill-current" />
          {pinnedMessages.length > 1 && (
            <span className="font-bold text-[10px] bg-blue-100 dark:bg-blue-900/50 text-blue-700 dark:text-blue-300 px-1.5 py-0.5 rounded-full tabular-nums">
              {safeIndex + 1}/{pinnedMessages.length}
            </span>
          )}
        </div>

        {/* Indicator bars for multiple pins */}
        {pinnedMessages.length > 1 && (
          <div className="flex flex-col gap-0.5 shrink-0">
            {pinnedMessages.slice(0, 4).map((msg, idx) => (
              <div
                key={msg.id}
                className={`w-0.5 h-1.5 rounded-full transition-colors ${
                  idx === safeIndex
                    ? 'bg-blue-600 dark:bg-blue-400'
                    : 'bg-slate-300 dark:bg-slate-600'
                }`}
              />
            ))}
          </div>
        )}

        {/* Content preview */}
        <button
          type="button"
          onClick={() => {
            if (pinnedMessages.length > 1) {
              handleCycle();
            } else {
              onJumpToMessage?.(current.id);
            }
          }}
          className="flex-1 min-w-0 text-left truncate hover:opacity-85 transition-opacity cursor-pointer"
        >
          <span className="font-bold text-blue-700 dark:text-blue-400 mr-1.5">
            {current.senderName || pinnedLabel}:
          </span>
          <span className="text-slate-700 dark:text-slate-300 font-medium">
            {getMessagePreviewText(current)}
          </span>
        </button>

        {/* Actions */}
        <div className="flex items-center gap-1 shrink-0">
          {pinnedMessages.length > 1 && (
            <button
              type="button"
              onClick={() => setExpanded((prev) => !prev)}
              className="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 rounded-md hover:bg-white/70 dark:hover:bg-slate-700/60 transition-colors cursor-pointer"
              title={expanded ? 'Thu gọn danh sách tin đã ghim' : 'Xem tất cả tin đã ghim'}
            >
              {expanded ? (
                <ChevronUp className="w-3.5 h-3.5" />
              ) : (
                <ChevronDown className="w-3.5 h-3.5" />
              )}
            </button>
          )}
          {canUnpin && onUnpin && (
            <button
              type="button"
              onClick={() => onUnpin(current.id)}
              className="p-1 text-slate-400 hover:text-red-500 rounded-md hover:bg-white/70 dark:hover:bg-slate-700/60 transition-colors cursor-pointer"
              title={unpinTitle}
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* Expanded list */}
      {expanded && pinnedMessages.length > 1 && (
        <div className="px-4 pb-2.5 pt-1 border-t border-slate-200/60 dark:border-slate-700/50 space-y-1 max-h-44 overflow-y-auto cliq-scrollbar">
          {pinnedMessages.map((pm, idx) => (
            <div
              key={pm.id}
              onClick={() => {
                setActiveIndex(idx);
                onJumpToMessage?.(pm.id);
                setExpanded(false);
              }}
              className={`flex items-center justify-between gap-2 px-2.5 py-1.5 rounded-lg text-xs cursor-pointer transition-colors ${
                idx === safeIndex
                  ? 'bg-blue-100/80 dark:bg-blue-900/35 text-blue-900 dark:text-blue-200'
                  : 'hover:bg-white/80 dark:hover:bg-slate-700/50 text-slate-700 dark:text-slate-300'
              }`}
            >
              <div className="min-w-0 flex-1 truncate">
                <span className="font-bold mr-1.5">{pm.senderName}:</span>
                <span className="opacity-85">{getMessagePreviewText(pm)}</span>
              </div>
              {canUnpin && onUnpin && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onUnpin(pm.id);
                  }}
                  className="p-0.5 text-slate-400 hover:text-red-500 rounded shrink-0 cursor-pointer"
                  title={unpinTitle}
                >
                  <X className="w-3 h-3" />
                </button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default PinnedMessageBar;
