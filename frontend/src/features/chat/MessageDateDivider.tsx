import React from 'react';
import { Calendar } from 'lucide-react';

export interface MessageDateDividerProps {
  label: string;
  onClick?: () => void;
}

export const MessageDateDivider: React.FC<MessageDateDividerProps> = ({
  label,
  onClick,
}) => {
  const pillContent = (
    <>
      <Calendar className="w-3 h-3 text-slate-400 dark:text-slate-500 shrink-0" />
      <span>{label}</span>
    </>
  );

  return (
    <div
      className="relative flex items-center justify-center my-4 select-none z-10"
      role="separator"
      aria-label={label}
    >
      <div className="flex-1 h-px bg-gradient-to-r from-transparent via-slate-200 dark:via-slate-800 to-slate-200 dark:to-slate-800" />
      {onClick ? (
        <button
          type="button"
          onClick={onClick}
          className="mx-3 inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-[11px] font-bold tracking-wide text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-800/95 border border-slate-200/90 dark:border-slate-700/80 shadow-2xs hover:border-blue-300 dark:hover:border-blue-500/40 hover:text-blue-600 dark:hover:text-blue-400 transition-all cursor-pointer"
        >
          {pillContent}
        </button>
      ) : (
        <div className="mx-3 inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-[11px] font-bold tracking-wide text-slate-500 dark:text-slate-400 bg-white/95 dark:bg-slate-800/90 border border-slate-200/80 dark:border-slate-700/70 shadow-2xs">
          {pillContent}
        </div>
      )}
      <div className="flex-1 h-px bg-gradient-to-l from-transparent via-slate-200 dark:via-slate-800 to-slate-200 dark:to-slate-800" />
    </div>
  );
};

export default MessageDateDivider;
