import React, { useState, useEffect, useRef } from 'react';
import { Play, Pause, Mic, Download, CheckCheck } from 'lucide-react';
import { apiClient } from '../../shared/api/apiClient';

export interface VoiceWaveformPlayerProps {
  src?: string;
  url?: string;
  filename?: string;
  duration?: string;
  durationSeconds?: number;
  timestamp?: string;
  isOutgoing?: boolean;
  compact?: boolean;
}

const BAR_COUNT = 28;

function parseDurationToSeconds(durationStr?: string): number {
  if (!durationStr) return 0;
  const parts = durationStr.trim().split(':').map(Number);
  if (parts.length === 2 && !Number.isNaN(parts[0]) && !Number.isNaN(parts[1])) {
    return parts[0] * 60 + parts[1];
  }
  return 0;
}

function formatSeconds(secs: number): string {
  if (!Number.isFinite(secs) || secs < 0) return '0:00';
  const m = Math.floor(secs / 60);
  const s = Math.floor(secs % 60);
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export const VoiceWaveformPlayer: React.FC<VoiceWaveformPlayerProps> = ({
  src,
  url,
  filename,
  duration,
  durationSeconds,
  timestamp,
  isOutgoing = false,
  compact = false,
}) => {
  const sourceUrl = src || url;
  const [loadedAsset, setLoadedAsset] = useState<{ source: string; url: string } | null>(null);
  const resolvedUrl = sourceUrl?.startsWith('data:') || sourceUrl?.startsWith('blob:')
    ? sourceUrl
    : loadedAsset && loadedAsset.source === sourceUrl ? loadedAsset.url : undefined;
  const initialDuration =
    typeof durationSeconds === 'number' && durationSeconds > 0
      ? durationSeconds
      : parseDurationToSeconds(duration);

  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [totalDuration, setTotalDuration] = useState<number>(initialDuration);
  const [decodedBars, setDecodedBars] = useState<number[] | null>(null);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const waveformRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!sourceUrl || sourceUrl.startsWith('data:') || sourceUrl.startsWith('blob:')) return undefined;
    const match = sourceUrl.match(/\/api\/files\/(\d+)/);
    if (!match) return undefined;
    let cancelled = false;
    let objectUrl: string | null = null;
    apiClient.getAttachmentObjectUrl(Number(match[1])).then((nextUrl) => {
      objectUrl = nextUrl;
      if (cancelled) URL.revokeObjectURL(nextUrl);
      else setLoadedAsset({ source: sourceUrl, url: nextUrl });
    }).catch(() => undefined);
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [sourceUrl]);

  const bars = resolvedUrl && decodedBars ? decodedBars : [];

  // Decode real audio peaks with Web Audio API when a source URL is provided
  useEffect(() => {
    if (!resolvedUrl) return;

    let cancelled = false;
    const controller = new AbortController();

    const decodeAudio = async () => {
      try {
        const res = await fetch(resolvedUrl, { signal: controller.signal });
        if (!res.ok) return;
        const arrayBuffer = await res.arrayBuffer();
        if (cancelled) return;
        const AudioCtx =
          window.AudioContext ||
          (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
        if (!AudioCtx) return;
        const ctx = new AudioCtx();
        try {
          const audioBuffer = await ctx.decodeAudioData(arrayBuffer);
          if (cancelled) return;
          if (audioBuffer.duration > 0) {
            setTotalDuration(audioBuffer.duration);
          }
          const rawData = audioBuffer.getChannelData(0);
          const blockSize = Math.max(1, Math.floor(rawData.length / BAR_COUNT));
          const computed: number[] = [];
          for (let i = 0; i < BAR_COUNT; i++) {
            let sum = 0;
            const start = i * blockSize;
            for (let j = 0; j < blockSize && start + j < rawData.length; j++) {
              sum += Math.abs(rawData[start + j]);
            }
            const avg = sum / blockSize;
            computed.push(Math.min(100, Math.max(22, Math.round(avg * 260))));
          }
          setDecodedBars(computed);
        } finally {
          void ctx.close().catch(() => {});
        }
      } catch {
        setDecodedBars(null);
      }
    };

    void decodeAudio();
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [resolvedUrl]);

  // Bind HTMLAudioElement events when resolvedUrl is present
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || !resolvedUrl) return;

    const handleTimeUpdate = () => setCurrentTime(audio.currentTime);
    const handleLoadedMetadata = () => {
      if (audio.duration && Number.isFinite(audio.duration)) {
        setTotalDuration(audio.duration);
      }
    };
    const handleEnded = () => {
      setIsPlaying(false);
      setCurrentTime(0);
    };

    audio.addEventListener('timeupdate', handleTimeUpdate);
    audio.addEventListener('loadedmetadata', handleLoadedMetadata);
    audio.addEventListener('ended', handleEnded);
    return () => {
      audio.removeEventListener('timeupdate', handleTimeUpdate);
      audio.removeEventListener('loadedmetadata', handleLoadedMetadata);
      audio.removeEventListener('ended', handleEnded);
    };
  }, [resolvedUrl]);

  const handleTogglePlay = async () => {
    if (resolvedUrl && audioRef.current) {
      if (isPlaying) {
        audioRef.current.pause();
        setIsPlaying(false);
      } else {
        try {
          await audioRef.current.play();
          setIsPlaying(true);
        } catch {
          setIsPlaying(false);
        }
      }
      return;
    }
  };

  const handleScrub = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!resolvedUrl || !waveformRef.current || totalDuration <= 0) return;
    const rect = waveformRef.current.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    const nextTime = ratio * totalDuration;
    setCurrentTime(nextTime);
    if (resolvedUrl && audioRef.current) {
      try {
        audioRef.current.currentTime = nextTime;
      } catch {
        // Ignore seek errors on unbuffered streams
      }
    }
  };

  const handleScrubKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (!resolvedUrl || totalDuration <= 0) return;
    const step = e.shiftKey ? 10 : 5;
    let nextTime: number;
    switch (e.key) {
      case 'ArrowLeft': nextTime = currentTime - step; break;
      case 'ArrowRight': nextTime = currentTime + step; break;
      case 'Home': nextTime = 0; break;
      case 'End': nextTime = totalDuration; break;
      default: return;
    }
    e.preventDefault();
    nextTime = Math.max(0, Math.min(totalDuration, nextTime));
    setCurrentTime(nextTime);
    if (audioRef.current) audioRef.current.currentTime = nextTime;
  };

  const progressRatio = totalDuration > 0 ? Math.min(1, currentTime / totalDuration) : 0;
  const displayedTime = !resolvedUrl
    ? 'Unavailable'
    : isPlaying || currentTime > 0
      ? formatSeconds(currentTime)
      : duration || formatSeconds(totalDuration);

  return (
    <div className={`flex flex-col ${isOutgoing ? 'items-end' : 'items-start'} my-1`}>
      <div
        className={`inline-flex items-center gap-3 rounded-2xl px-3.5 py-2.5 bg-white/90 dark:bg-slate-900/75 border border-slate-200/90 dark:border-slate-700/80 shadow-2xs select-none ${
          compact ? 'min-w-[230px]' : 'min-w-[260px] sm:min-w-[288px]'
        }`}
      >
        {resolvedUrl && <audio ref={audioRef} src={resolvedUrl} preload="metadata" className="hidden" />}

        {/* Circular Play / Pause Button in Cliq Royal Blue */}
        <button
          type="button"
          onClick={() => void handleTogglePlay()}
          disabled={!resolvedUrl}
          aria-label={isPlaying ? 'Tạm dừng ghi âm' : 'Phát tin nhắn thoại'}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[#2563eb] hover:bg-[#1d4ed8] text-white shadow-xs transition-transform hover:scale-105 active:scale-95 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {isPlaying ? (
            <Pause className="h-3.5 w-3.5 fill-current" />
          ) : (
            <Play className="h-3.5 w-3.5 fill-current ml-0.5" />
          )}
        </button>

        {/* Waveform Progress + Header */}
        <div className="flex flex-1 flex-col gap-1 min-w-0">
          <div className="flex items-center justify-between gap-2 text-[10.5px] font-bold text-slate-500 dark:text-slate-400">
            <span className="inline-flex items-center gap-1 truncate text-slate-700 dark:text-slate-200">
              <Mic className="h-3 w-3 text-[#2563eb] dark:text-blue-400 shrink-0" />
              <span className="truncate">{filename || 'Tin nhắn thoại'}</span>
            </span>
            <span className="font-mono text-[11px] font-bold text-slate-600 dark:text-slate-300 tabular-nums shrink-0">
              {displayedTime}
            </span>
          </div>

          <div
            ref={waveformRef}
            onClick={handleScrub}
            onKeyDown={handleScrubKeyDown}
            role="slider"
            aria-label="Thanh tiến trình âm thanh"
            aria-valuemin={0}
            aria-valuemax={Math.round(totalDuration)}
            aria-valuenow={Math.round(currentTime)}
            tabIndex={resolvedUrl ? 0 : -1}
            aria-disabled={!resolvedUrl}
            className={`flex items-center gap-[3px] h-6 py-0.5 ${resolvedUrl ? 'cursor-pointer' : 'cursor-not-allowed'}`}
          >
            {!resolvedUrl ? <span className="text-[11px] text-slate-400">Không thể phát tệp âm thanh</span> : bars.map((pct, idx) => {
              const barRatio = (idx + 1) / bars.length;
              const isPlayed = isPlaying || currentTime > 0 ? barRatio <= progressRatio : false;
              return (
                <span
                  key={idx}
                  style={{ height: `${Math.max(20, pct)}%` }}
                  className={`flex-1 min-w-[2.5px] max-w-[3.5px] rounded-full transition-colors duration-100 ${
                    isPlayed
                      ? 'bg-[#2563eb] dark:bg-blue-400'
                      : 'bg-slate-300 dark:bg-slate-700'
                  }`}
                />
              );
            })}
          </div>
        </div>

        {/* Optional Download Link or Outgoing Delivery Indicator */}
        {resolvedUrl ? (
          <a
            href={resolvedUrl}
            download={filename || 'voice-message.webm'}
            target="_blank"
            rel="noopener noreferrer"
            onClick={(e) => e.stopPropagation()}
            title="Tải xuống tin nhắn thoại"
            aria-label="Tải xuống tin nhắn thoại"
            className="p-1.5 rounded-xl text-slate-400 hover:text-[#2563eb] dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-slate-800 transition-colors shrink-0"
          >
            <Download className="h-3.5 w-3.5" />
          </a>
        ) : (
          isOutgoing && (
            <div className="flex items-center shrink-0 pl-0.5">
              <CheckCheck className="h-3.5 w-3.5 text-[#2563eb] dark:text-blue-400" />
            </div>
          )
        )}
      </div>

      {timestamp && (
        <div className="flex items-center gap-1 mt-1 px-1.5 text-[10.5px] text-slate-400 dark:text-slate-500 font-semibold tabular-nums">
          <span>{timestamp}</span>
        </div>
      )}
    </div>
  );
};

export default VoiceWaveformPlayer;
