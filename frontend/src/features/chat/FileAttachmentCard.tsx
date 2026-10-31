import React, { useEffect, useState } from 'react';
import { Download, ExternalLink, Maximize2 } from 'lucide-react';
import type { Attachment } from '../../shared/types/api';
import { apiClient } from '../../shared/api/apiClient';
import { FileCategoryIcon } from './FileTypeIcons';

export interface FileAttachmentCardProps {
  attachment: Attachment;
  resolvedUrl: string;
  isOwn?: boolean;
  onPreviewImage?: (url: string, filename: string) => void;
}

function formatFileSize(bytes?: number): string {
  if (!bytes || bytes <= 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  return `${mb.toFixed(1)} MB`;
}

function isImageAttachment(filename: string, contentType?: string): boolean {
  const lower = (filename || '').toLowerCase();
  const mime = (contentType || '').toLowerCase();
  return mime.startsWith('image/') || /\.(png|jpe?g|gif|webp|svg|bmp|ico|avif)$/.test(lower);
}

function isVideoAttachment(filename: string, contentType?: string): boolean {
  const lower = (filename || '').toLowerCase();
  const mime = (contentType || '').toLowerCase();
  return mime.startsWith('video/') || /\.(mp4|mov|avi|mkv)$/.test(lower);
}

export const FileAttachmentCard: React.FC<FileAttachmentCardProps> = ({
  attachment,
  resolvedUrl,
  isOwn = false,
  onPreviewImage,
}) => {
  const [loadedAsset, setLoadedAsset] = useState<{ id: number; url: string } | null>(null);
  const assetUrl = resolvedUrl.startsWith('data:')
    ? resolvedUrl
    : loadedAsset?.id === attachment.id ? loadedAsset.url : null;
  useEffect(() => {
    if (resolvedUrl.startsWith('data:')) return undefined;
    let cancelled = false;
    let objectUrl: string | null = null;
    apiClient.getAttachmentObjectUrl(attachment.id).then((url) => {
      objectUrl = url;
      if (cancelled) URL.revokeObjectURL(url);
      else setLoadedAsset({ id: attachment.id, url });
    }).catch(() => undefined);
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [attachment.id, resolvedUrl]);

  const isImage = isImageAttachment(attachment.filename, attachment.contentType);
  const isVideo = isVideoAttachment(attachment.filename, attachment.contentType);
  const sizeLabel = formatFileSize(attachment.sizeBytes);
  const ext = attachment.filename.includes('.')
    ? attachment.filename.split('.').pop()?.toUpperCase()
    : 'FILE';

  if (isImage) {
    return (
      <div className="group/img relative inline-block overflow-hidden rounded-2xl border border-slate-200/80 dark:border-slate-700/70 bg-slate-100/60 dark:bg-slate-800/60 shadow-2xs max-w-[300px]">
          {assetUrl ? <img
          src={assetUrl}
          alt={attachment.filename}
          loading="lazy"
          onClick={() => assetUrl && onPreviewImage?.(assetUrl, attachment.filename)}
          className={`max-h-[220px] w-auto object-cover transition-transform duration-200 group-hover/img:scale-[1.015] ${
            onPreviewImage ? 'cursor-zoom-in' : ''
          }`}
          /> : <div className="flex h-32 w-56 items-center justify-center text-xs text-slate-500">Không thể xem tệp này</div>}
        <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-2 bg-gradient-to-t from-slate-950/75 via-slate-950/35 to-transparent px-3 py-2 opacity-0 group-hover/img:opacity-100 transition-opacity">
          <span className="text-[11px] font-semibold text-white truncate">
            {attachment.filename}
          </span>
          <div className="flex items-center gap-1 shrink-0">
            {onPreviewImage && (
              <button
                type="button"
                onClick={() => assetUrl && onPreviewImage(assetUrl, attachment.filename)}
                className="p-1 rounded-lg bg-white/20 hover:bg-white/30 text-white backdrop-blur-xs transition-colors cursor-pointer"
                title="Xem ảnh"
              >
                <Maximize2 className="w-3.5 h-3.5" />
              </button>
            )}
            <a
              href={assetUrl ?? undefined}
              target="_blank"
              rel="noopener noreferrer"
              download={attachment.filename}
              onClick={(e) => { e.stopPropagation(); if (!assetUrl) e.preventDefault(); }}
              className="p-1 rounded-lg bg-white/20 hover:bg-white/30 text-white backdrop-blur-xs transition-colors"
              title="Mở hoặc tải xuống"
            >
              <Download className="w-3.5 h-3.5" />
            </a>
          </div>
        </div>
      </div>
    );
  }

  return (
    <a
      href={assetUrl ?? undefined}
      target="_blank"
      rel="noopener noreferrer"
      download={attachment.filename}
      onClick={(event) => { if (!assetUrl) event.preventDefault(); }}
      className={`group/file flex items-center gap-3 p-2.5 rounded-xl border transition-all max-w-[310px] no-underline ${
        isOwn
          ? 'bg-white/85 dark:bg-slate-900/60 border-blue-200/80 dark:border-blue-500/30 hover:bg-white dark:hover:bg-slate-900/80'
          : 'bg-white dark:bg-slate-900/75 border-slate-200/90 dark:border-slate-700/75 hover:border-blue-300 dark:hover:border-blue-500/40'
      } shadow-2xs`}
    >
      <FileCategoryIcon
        filename={attachment.filename}
        contentType={attachment.contentType}
        className="w-9 h-9 shrink-0"
      />
      <div className="min-w-0 flex-1">
        <p className="text-[12.5px] font-bold text-slate-800 dark:text-slate-100 truncate group-hover/file:text-blue-600 dark:group-hover/file:text-blue-400 transition-colors">
          {attachment.filename}
        </p>
        <div className="flex items-center gap-1.5 text-[10.5px] font-semibold text-slate-400 dark:text-slate-400 mt-0.5">
          {ext && <span>{ext}</span>}
          {ext && sizeLabel && <span>•</span>}
          {sizeLabel && <span className="tabular-nums">{sizeLabel}</span>}
        </div>
      </div>
      <div className="w-7 h-7 rounded-lg flex items-center justify-center text-slate-400 group-hover/file:text-blue-600 dark:group-hover/file:text-blue-400 group-hover/file:bg-blue-50 dark:group-hover/file:bg-blue-950/50 transition-colors shrink-0">
        {isVideo ? (
          <ExternalLink className="w-3.5 h-3.5" />
        ) : (
          <Download className="w-3.5 h-3.5" />
        )}
      </div>
    </a>
  );
};

export default FileAttachmentCard;
