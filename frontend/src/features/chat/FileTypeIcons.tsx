import React from 'react';

interface IconProps {
  className?: string;
}

export const PdfIcon: React.FC<IconProps> = ({ className = 'w-8 h-8' }) => (
  <svg viewBox="0 0 40 40" fill="none" className={className}>
    <rect width="40" height="40" rx="10" fill="#FEE2E2" className="dark:fill-red-950/60" />
    <path d="M13 10h9l7 7v13a2 2 0 01-2 2H13a2 2 0 01-2-2V12a2 2 0 012-2z" fill="#EF4444" fillOpacity="0.15" stroke="#EF4444" strokeWidth="1.5" />
    <path d="M22 10v5a2 2 0 002 2h5" stroke="#EF4444" strokeWidth="1.5" strokeLinecap="round" />
    <text x="20" y="27" textAnchor="middle" fill="#DC2626" fontSize="7.5" fontWeight="800" fontFamily="sans-serif">PDF</text>
  </svg>
);

export const WordIcon: React.FC<IconProps> = ({ className = 'w-8 h-8' }) => (
  <svg viewBox="0 0 40 40" fill="none" className={className}>
    <rect width="40" height="40" rx="10" fill="#DBEAFE" className="dark:fill-blue-950/60" />
    <path d="M13 10h9l7 7v13a2 2 0 01-2 2H13a2 2 0 01-2-2V12a2 2 0 012-2z" fill="#3B82F6" fillOpacity="0.15" stroke="#2563EB" strokeWidth="1.5" />
    <path d="M22 10v5a2 2 0 002 2h5" stroke="#2563EB" strokeWidth="1.5" strokeLinecap="round" />
    <text x="20" y="27" textAnchor="middle" fill="#1D4ED8" fontSize="7" fontWeight="800" fontFamily="sans-serif">DOC</text>
  </svg>
);

export const ExcelIcon: React.FC<IconProps> = ({ className = 'w-8 h-8' }) => (
  <svg viewBox="0 0 40 40" fill="none" className={className}>
    <rect width="40" height="40" rx="10" fill="#DCFCE7" className="dark:fill-emerald-950/60" />
    <path d="M13 10h9l7 7v13a2 2 0 01-2 2H13a2 2 0 01-2-2V12a2 2 0 012-2z" fill="#22C55E" fillOpacity="0.15" stroke="#16A34A" strokeWidth="1.5" />
    <path d="M22 10v5a2 2 0 002 2h5" stroke="#16A34A" strokeWidth="1.5" strokeLinecap="round" />
    <text x="20" y="27" textAnchor="middle" fill="#15803D" fontSize="7.5" fontWeight="800" fontFamily="sans-serif">XLS</text>
  </svg>
);

export const ArchiveIcon: React.FC<IconProps> = ({ className = 'w-8 h-8' }) => (
  <svg viewBox="0 0 40 40" fill="none" className={className}>
    <rect width="40" height="40" rx="10" fill="#FEF3C7" className="dark:fill-amber-950/60" />
    <path d="M13 10h9l7 7v13a2 2 0 01-2 2H13a2 2 0 01-2-2V12a2 2 0 012-2z" fill="#F59E0B" fillOpacity="0.15" stroke="#D97706" strokeWidth="1.5" />
    <path d="M18 13h2m-2 3h2m-2 3h2" stroke="#D97706" strokeWidth="1.5" strokeLinecap="round" />
    <text x="20" y="28" textAnchor="middle" fill="#B45309" fontSize="7" fontWeight="800" fontFamily="sans-serif">ZIP</text>
  </svg>
);

export const AudioIcon: React.FC<IconProps> = ({ className = 'w-8 h-8' }) => (
  <svg viewBox="0 0 40 40" fill="none" className={className}>
    <rect width="40" height="40" rx="10" fill="#E0E7FF" className="dark:fill-indigo-950/60" />
    <circle cx="20" cy="20" r="9" fill="#6366F1" fillOpacity="0.15" stroke="#4F46E5" strokeWidth="1.5" />
    <path d="M18 23V16l6-1.5V21" stroke="#4F46E5" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    <circle cx="16.5" cy="23" r="1.5" fill="#4F46E5" />
    <circle cx="22.5" cy="21.5" r="1.5" fill="#4F46E5" />
  </svg>
);

export const VideoIcon: React.FC<IconProps> = ({ className = 'w-8 h-8' }) => (
  <svg viewBox="0 0 40 40" fill="none" className={className}>
    <rect width="40" height="40" rx="10" fill="#FCE7F3" className="dark:fill-pink-950/60" />
    <rect x="11" y="13" width="18" height="14" rx="3" fill="#EC4899" fillOpacity="0.15" stroke="#DB2777" strokeWidth="1.5" />
    <path d="M18 17.5l5 2.5-5 2.5v-5z" fill="#DB2777" />
  </svg>
);

export const CodeFileIcon: React.FC<IconProps> = ({ className = 'w-8 h-8' }) => (
  <svg viewBox="0 0 40 40" fill="none" className={className}>
    <rect width="40" height="40" rx="10" fill="#E0F2FE" className="dark:fill-sky-950/60" />
    <path d="M16 16l-4 4 4 4m8-8l4 4-4 4m-2-10l-4 12" stroke="#0284C7" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

export const GenericFileIcon: React.FC<IconProps> = ({ className = 'w-8 h-8' }) => (
  <svg viewBox="0 0 40 40" fill="none" className={className}>
    <rect width="40" height="40" rx="10" fill="#F1F5F9" className="dark:fill-slate-800" />
    <path d="M13 10h9l7 7v13a2 2 0 01-2 2H13a2 2 0 01-2-2V12a2 2 0 012-2z" fill="#64748B" fillOpacity="0.12" stroke="#64748B" strokeWidth="1.5" />
    <path d="M22 10v5a2 2 0 002 2h5M15 22h10M15 26h7" stroke="#64748B" strokeWidth="1.5" strokeLinecap="round" />
  </svg>
);

export type FileCategory =
  | 'image'
  | 'pdf'
  | 'word'
  | 'excel'
  | 'archive'
  | 'audio'
  | 'video'
  | 'code'
  | 'generic';

function resolveCategory(filename?: string, contentType?: string): FileCategory {
  const lower = (filename || '').toLowerCase();
  const mime = (contentType || '').toLowerCase();

  if (mime.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg|bmp|ico|avif)$/.test(lower)) return 'image';
  if (mime === 'application/pdf' || lower.endsWith('.pdf')) return 'pdf';
  if (mime.includes('word') || /\.(doc|docx|rtf|odt)$/.test(lower)) return 'word';
  if (mime.includes('excel') || mime.includes('spreadsheet') || /\.(xls|xlsx|csv|ods)$/.test(lower)) return 'excel';
  if (mime.includes('zip') || mime.includes('compressed') || /\.(zip|rar|7z|tar|gz|bz2)$/.test(lower)) return 'archive';
  if (mime.startsWith('audio/') || /\.(mp3|wav|ogg|m4a|flac|aac|webm)$/.test(lower)) return 'audio';
  if (mime.startsWith('video/') || /\.(mp4|mov|avi|mkv)$/.test(lower)) return 'video';
  if (/\.(js|ts|tsx|jsx|py|java|c|cpp|cs|go|rs|html|css|json|xml|yaml|yml|sql|sh|md)$/.test(lower)) return 'code';
  return 'generic';
}

export const FileCategoryIcon: React.FC<{
  category?: FileCategory;
  filename?: string;
  contentType?: string;
  className?: string;
}> = ({ category, filename, contentType, className = 'w-9 h-9' }) => {
  const resolved = category || resolveCategory(filename, contentType);
  switch (resolved) {
    case 'pdf':
      return <PdfIcon className={className} />;
    case 'word':
      return <WordIcon className={className} />;
    case 'excel':
      return <ExcelIcon className={className} />;
    case 'archive':
      return <ArchiveIcon className={className} />;
    case 'audio':
      return <AudioIcon className={className} />;
    case 'video':
      return <VideoIcon className={className} />;
    case 'code':
      return <CodeFileIcon className={className} />;
    default:
      return <GenericFileIcon className={className} />;
  }
};
