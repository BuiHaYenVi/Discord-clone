import React, { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { Hand, Heart, PartyPopper, Search, Smile, Sparkles } from 'lucide-react';
import { AnimatePresence, motion } from 'framer-motion';
import { TEAMS_EMOJI_CATEGORIES, type TeamsEmojiCategoryId } from './TeamsEmojiCatalog';
import { TeamsEmoji } from './TeamsEmoji';

interface ReactionPickerPopoverProps {
  isOpen: boolean;
  triggerRef?: RefObject<HTMLElement | null>;
  onClose: () => void;
  onSelectEmoji: (emoji: string) => void;
  className?: string;
  placement?: 'above' | 'below';
}

const CATEGORY_ICONS = {
  popular: Sparkles,
  smileys: Smile,
  gestures: Hand,
  feelings: Heart,
  activities: PartyPopper,
} as const;

const CATEGORY_LABELS: Record<TeamsEmojiCategoryId, string> = {
  popular: 'Phổ biến',
  smileys: 'Khuôn mặt',
  gestures: 'Cử chỉ',
  feelings: 'Cảm xúc',
  activities: 'Hoạt động',
};

export const ReactionPickerPopover: React.FC<ReactionPickerPopoverProps> = ({
  isOpen,
  triggerRef,
  onClose,
  onSelectEmoji,
  className = 'right-0',
  placement = 'above',
}) => {
  const [activeCategory, setActiveCategory] = useState<TeamsEmojiCategoryId>('popular');
  const [query, setQuery] = useState('');
  const normalizedQuery = query.trim().toLowerCase();
  const visibleEmoji = useMemo(() => {
    const categories = normalizedQuery
      ? TEAMS_EMOJI_CATEGORIES
      : TEAMS_EMOJI_CATEGORIES.filter((category) => category.id === activeCategory);
    return categories.flatMap((category) => category.emoji).filter((emoji) =>
      !normalizedQuery || emoji.keywords.some((keyword) => keyword.toLowerCase().includes(normalizedQuery)),
    );
  }, [activeCategory, normalizedQuery]);

  const popoverRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!isOpen) return undefined;
    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (popoverRef.current?.contains(target) || triggerRef?.current?.contains(target)) return;
      onClose();
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen, onClose, triggerRef]);

  return (
    <AnimatePresence>
      {isOpen && (
        <motion.div
          ref={popoverRef}
          role="dialog"
          aria-label="Chọn cảm xúc"
          initial={{ opacity: 0, scale: 0.96, y: 8 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          exit={{ opacity: 0, scale: 0.96, y: 8 }}
          transition={{ type: 'spring', stiffness: 420, damping: 30 }}
          className={`absolute ${placement === 'below' ? 'top-full mt-2' : 'bottom-full mb-2'} z-[350] w-[min(344px,calc(100vw-24px))] overflow-hidden rounded-2xl border border-slate-200/90 bg-white/98 shadow-2xl shadow-slate-950/15 backdrop-blur-xl dark:border-slate-700 dark:bg-slate-900/95 ${className}`}
          onPointerDown={(event) => event.stopPropagation()}
        >
          <div className="border-b border-slate-100 px-3 pb-2 pt-3 dark:border-slate-800">
            <label className="flex h-9 items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-2.5 text-slate-500 focus-within:border-blue-400 focus-within:bg-white focus-within:ring-2 focus-within:ring-blue-100 dark:border-slate-700 dark:bg-slate-800 dark:focus-within:bg-slate-800">
              <Search size={15} aria-hidden="true" />
              <input
                autoFocus
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Tìm cảm xúc"
                aria-label="Tìm cảm xúc"
                className="min-w-0 flex-1 bg-transparent text-xs font-semibold text-slate-700 outline-none placeholder:text-slate-400 dark:text-slate-100"
              />
            </label>
          </div>
          <div className="flex items-center justify-between gap-0.5 border-b border-slate-100 px-2 py-1.5 dark:border-slate-800" role="tablist" aria-label="Nhóm cảm xúc">
            {TEAMS_EMOJI_CATEGORIES.map((category) => {
              const Icon = CATEGORY_ICONS[category.id];
              const isActive = category.id === activeCategory && !normalizedQuery;
              return (
                <button
                  key={category.id}
                  type="button"
                  role="tab"
                  aria-selected={isActive}
                  title={CATEGORY_LABELS[category.id]}
                  aria-label={CATEGORY_LABELS[category.id]}
                  onClick={() => {
                    setActiveCategory(category.id);
                    setQuery('');
                  }}
                  className={`flex h-8 w-12 items-center justify-center rounded-lg transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 ${isActive ? 'bg-blue-50 text-[#2563eb] dark:bg-blue-950/50 dark:text-blue-300' : 'text-slate-400 hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-800 dark:hover:text-slate-200'}`}
                >
                  <Icon size={16} strokeWidth={2.2} aria-hidden="true" />
                </button>
              );
            })}
          </div>
          <div className="grid max-h-[276px] grid-cols-8 gap-1 overflow-y-auto px-3 py-2.5">
            {visibleEmoji.map(({ emoji, name }) => (
              <motion.button
                key={emoji}
                type="button"
                title={name}
                aria-label={name}
                whileHover={{ scale: 1.22 }}
                whileTap={{ scale: 0.92 }}
                transition={{ type: 'spring', stiffness: 520, damping: 22 }}
                onClick={() => {
                  onSelectEmoji(emoji);
                  onClose();
                }}
                className="flex h-9 items-center justify-center rounded-lg hover:bg-blue-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 dark:hover:bg-slate-800"
              >
                <TeamsEmoji emoji={emoji} alt={name} size={27} />
              </motion.button>
            ))}
            {!visibleEmoji.length && <p className="col-span-8 py-8 text-center text-xs font-semibold text-slate-400">Không tìm thấy cảm xúc</p>}
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
};
