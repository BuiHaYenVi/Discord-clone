import React, { useState, useRef, useEffect } from 'react';
import {
  Paperclip,
  Send,
  Smile,
  X,
  Reply,
  Edit3,
  FileText,
  Image as ImageIcon,
  AtSign,
  AlertCircle,
  Mic,
  Square,
  Trash2,
} from 'lucide-react';
import { useAppStore } from '../../shared/state/appStore';
import { userFacingError } from '../../shared/ui/userFacingError';
import type { Attachment } from '../../shared/types/api';

const MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
const QUICK_EMOJIS = ['👍', '❤️', '😂', '🔥', '🎉', '👀', '🚀', '💯'];

export interface MessageComposerProps {
  mode?: 'channel' | 'dm';
  placeholder?: string;
}

interface PendingAttachmentItem {
  localId: string;
  file: File;
  previewUrl?: string;
  uploading: boolean;
  uploaded?: Attachment;
  error?: string;
}

function getMentionRoleLabel(role?: string): string | null {
  switch (role) {
    case 'BROADCAST': return 'Cả kênh';
    case 'OWNER': return 'Chủ nhóm';
    case 'ADMIN': return 'Quản trị viên';
    case 'MEMBER': return 'Thành viên';
    default: return null;
  }
}

export const MessageComposer: React.FC<MessageComposerProps> = ({
  mode,
  placeholder,
}) => {
  const {
    currentUser,
    viewMode,
    activeChannel,
    members,
    friends,
    typingUsers,
    replyingTo,
    editingMessage,
    setReplyingTo,
    setEditingMessage,
    sendMessage,
    sendDmMessage,
    editMessage,
    uploadFile,
    notifyTyping,
    locale,
  } = useAppStore();

  const effectiveMode = mode || (viewMode === 'dm' ? 'dm' : 'channel');

  const [content, setContent] = useState('');
  const [attachments, setAttachments] = useState<PendingAttachmentItem[]>([]);
  const attachmentsRef = useRef<PendingAttachmentItem[]>([]);
  const [isDraggingOver, setIsDraggingOver] = useState(false);
  const [fileError, setFileError] = useState<string | null>(null);
  const [sendError, setSendError] = useState<string | null>(null);
  const [isSending, setIsSending] = useState(false);
  const [isEmojiPickerOpen, setIsEmojiPickerOpen] = useState(false);

  // Voice recording state
  const [isRecordingVoice, setIsRecordingVoice] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recordingStreamRef = useRef<MediaStream | null>(null);
  const recordedChunksRef = useRef<Blob[]>([]);
  const voiceStartInFlightRef = useRef(false);
  const voiceStartRequestRef = useRef(0);
  const isMountedRef = useRef(false);

  // @mention autocomplete state
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);

  // IME composition tracking (Vietnamese Telex/VNI safety)
  const isComposingRef = useRef<boolean>(false);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const lastTypingSentRef = useRef<number>(0);

  useEffect(() => {
    attachmentsRef.current = attachments;
  }, [attachments]);

  // Focus textarea when editingMessage or replyingTo changes
  useEffect(() => {
    if (editingMessage || replyingTo) {
      textareaRef.current?.focus();
    }
  }, [editingMessage, replyingTo]);

  // Voice recording timer
  useEffect(() => {
    if (!isRecordingVoice) return;
    const timer = setInterval(() => {
      setRecordingSeconds((prev) => prev + 1);
    }, 1000);
    return () => clearInterval(timer);
  }, [isRecordingVoice]);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      voiceStartRequestRef.current += 1;

      const recorder = mediaRecorderRef.current;
      mediaRecorderRef.current = null;
      if (recorder) {
        recorder.ondataavailable = null;
        recorder.onerror = null;
        if (recorder.state !== 'inactive') {
          try {
            recorder.stop();
          } catch {
            // The recorder may already have stopped as the component unmounted.
          }
        }
      }

      const stream = recordingStreamRef.current ?? recorder?.stream;
      recordingStreamRef.current = null;
      stream?.getTracks().forEach((track) => track.stop());
      recordedChunksRef.current = [];
      attachmentsRef.current.forEach((attachment) => {
        if (attachment.previewUrl) URL.revokeObjectURL(attachment.previewUrl);
      });
    };
  }, []);

  const formatRecordingDuration = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  // Build mention candidates list (@all + channel members or friends)
  const mentionCandidates = React.useMemo(() => {
    const list: { username: string; displayName: string; role?: string }[] = [
      {
        username: 'all',
        displayName: 'Tất cả thành viên trong kênh (@all)',
        role: 'BROADCAST',
      },
    ];
    const seen = new Set<string>(['all']);

    if (Array.isArray(members)) {
      for (const m of members) {
        const uname = m.username;
        if (uname && !seen.has(uname)) {
          seen.add(uname);
          list.push({
            username: uname,
            displayName: m.displayName || uname,
            role: m.role,
          });
        }
      }
    }
    if (Array.isArray(friends)) {
      for (const f of friends) {
        if (f.username && !seen.has(f.username)) {
          seen.add(f.username);
          list.push({
            username: f.username,
            displayName: f.displayName || f.username,
          });
        }
      }
    }
    if (mentionQuery === null) return [];
    const q = mentionQuery.toLowerCase();
    return list
      .filter(
        (item) =>
          item.username.toLowerCase().includes(q) ||
          item.displayName.toLowerCase().includes(q)
      )
      .slice(0, 6);
  }, [members, friends, mentionQuery]);

  const triggerThrottledTyping = () => {
    const now = Date.now();
    if (now - lastTypingSentRef.current > 2200) {
      lastTypingSentRef.current = now;
      try {
        notifyTyping(true);
      } catch {
        // Ignore if socket not connected
      }
    }
  };

  const handleTextChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const val = e.target.value;
    setContent(val);
    setSendError(null);
    triggerThrottledTyping();

    // Detect @mention token right before cursor
    const cursor = e.target.selectionStart ?? val.length;
    const textBeforeCursor = val.slice(0, cursor);
    const match = textBeforeCursor.match(/(?:^|\s)@([a-zA-Z0-9_.-]*)$/);
    if (match) {
      setMentionQuery(match[1]);
      setMentionIndex(0);
    } else {
      setMentionQuery(null);
    }
  };

  const insertMention = (username: string) => {
    if (!textareaRef.current) return;
    const cursor = textareaRef.current.selectionStart ?? content.length;
    const textBeforeCursor = content.slice(0, cursor);
    const textAfterCursor = content.slice(cursor);
    const replacedBefore = textBeforeCursor.replace(
      /(?:^|\s)@([a-zA-Z0-9_.-]*)$/,
      (full) => (full.startsWith(' ') ? ` @${username} ` : `@${username} `)
    );
    const nextValue = replacedBefore + textAfterCursor;
    setContent(nextValue);
    setMentionQuery(null);
    textareaRef.current.focus();
  };

  const processFiles = async (fileList: FileList | File[]) => {
    setFileError(null);
    const filesArray = Array.from(fileList);

    for (const file of filesArray) {
      if (file.size > MAX_FILE_SIZE_BYTES) {
        setFileError(
          `Tệp "${file.name}" vượt quá giới hạn 5 MB (${(
            file.size /
            (1024 * 1024)
          ).toFixed(2)} MB).`
        );
        continue;
      }

      const localId = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
      const isImage = file.type.startsWith('image/');
      const previewUrl = isImage ? URL.createObjectURL(file) : undefined;

      const newItem: PendingAttachmentItem = {
        localId,
        file,
        previewUrl,
        uploading: true,
      };

      setAttachments((prev) => [...prev, newItem]);

      try {
        const uploaded = await uploadFile(file);
        setAttachments((prev) =>
          prev.map((item) =>
            item.localId === localId
              ? {
                  ...item,
                  uploading: false,
                  uploaded:
                      uploaded,
                }
              : item
          )
        );
      } catch (err) {
        setAttachments((prev) =>
          prev.map((item) =>
            item.localId === localId
              ? {
                  ...item,
                  uploading: false,
                  error:
                    userFacingError(err, { locale, action: 'upload attachment', fallback: 'Không thể tải tệp lên. Vui lòng thử lại.', fallbackEn: 'The file could not be uploaded. Please try again.' }),
                }
              : item
          )
        );
      }
    }
  };

  const handleStartVoiceRecording = async () => {
    if (voiceStartInFlightRef.current || mediaRecorderRef.current?.state === 'recording') return;
    voiceStartInFlightRef.current = true;
    const requestId = ++voiceStartRequestRef.current;
    let stream: MediaStream | null = null;
    setFileError(null);
    setRecordingSeconds(0);
    recordedChunksRef.current = [];
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      setFileError('Trình duyệt này không hỗ trợ ghi âm.');
      voiceStartInFlightRef.current = false;
      return;
    }
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (!isMountedRef.current || requestId !== voiceStartRequestRef.current) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      const previousRecorder = mediaRecorderRef.current;
      if (previousRecorder && previousRecorder.state !== 'inactive') {
        try {
          previousRecorder.stop();
        } catch {
          // The previous recorder may already have stopped.
        }
      }
      recordingStreamRef.current?.getTracks().forEach((track) => track.stop());
      const recorder = new MediaRecorder(stream);
      recorder.ondataavailable = (evt) => {
        if (evt.data.size > 0) recordedChunksRef.current.push(evt.data);
      };
      recorder.start();
      mediaRecorderRef.current = recorder;
      recordingStreamRef.current = stream;
      setIsRecordingVoice(true);
    } catch (error) {
      stream?.getTracks().forEach((track) => track.stop());
      if (!isMountedRef.current) return;
      setFileError(userFacingError(error, { locale, action: 'voice recording', fallback: 'Không thể hoàn tất ghi âm. Vui lòng thử lại.', fallbackEn: 'The voice message could not be recorded. Please try again.' }));
    } finally {
      voiceStartInFlightRef.current = false;
    }
  };

  const handleCancelVoiceRecording = () => {
    voiceStartRequestRef.current += 1;
    if (mediaRecorderRef.current) {
      try {
        mediaRecorderRef.current.stream.getTracks().forEach((t) => t.stop());
        mediaRecorderRef.current.stop();
      } catch {
        // ignore
      }
      mediaRecorderRef.current = null;
    }
    recordingStreamRef.current?.getTracks().forEach((track) => track.stop());
    recordingStreamRef.current = null;
    recordedChunksRef.current = [];
    setRecordingSeconds(0);
    setIsRecordingVoice(false);
  };

  const handleFinishVoiceRecording = async () => {
    const recorder = mediaRecorderRef.current;
    setRecordingSeconds(0);
    setIsRecordingVoice(false);
    if (!recorder) {
      setFileError('Không có bản ghi âm để gửi.');
      return;
    }
    try {
      const blob = await new Promise<Blob>((resolve, reject) => {
        recorder.onstop = () => resolve(new Blob(recordedChunksRef.current, { type: recorder.mimeType || 'audio/webm' }));
        recorder.onerror = () => reject(new Error('Ghi âm thất bại.'));
        recorder.stop();
      });
      recorder.stream.getTracks().forEach((track) => track.stop());
      recordingStreamRef.current = null;
      if (!isMountedRef.current) return;
      if (blob.size === 0) throw new Error('Bản ghi âm trống. Hãy ghi lại rồi thử gửi.');
      const mimeType = blob.type || recorder.mimeType || 'audio/webm';
      const mimeSubtype = mimeType.split('/')[1]?.split(';')[0]?.toLowerCase();
      const extension = mimeSubtype === 'ogg' ? 'ogg'
        : mimeSubtype === 'mp4' ? 'm4a'
          : mimeSubtype === 'mpeg' ? 'mp3'
            : mimeSubtype === 'wav' || mimeSubtype === 'x-wav' ? 'wav'
              : 'webm';
      const voiceFile = new File([blob], `voice-${Date.now()}.${extension}`, { type: mimeType });
      await processFiles([voiceFile]);
    } catch (error) {
      recorder.stream.getTracks().forEach((track) => track.stop());
      recordingStreamRef.current = null;
      if (!isMountedRef.current) return;
      setFileError(userFacingError(error, { locale, action: 'voice recording', fallback: 'Không thể hoàn tất ghi âm. Vui lòng thử lại.', fallbackEn: 'The voice message could not be recorded. Please try again.' }));
    } finally {
      if (mediaRecorderRef.current === recorder) mediaRecorderRef.current = null;
    }
  };

  const handlePaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    const pastedFiles: File[] = [];
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (item.kind === 'file') {
        const f = item.getAsFile();
        if (f) pastedFiles.push(f);
      }
    }
    if (pastedFiles.length > 0) {
      e.preventDefault();
      void processFiles(pastedFiles);
    }
  };

  const handleDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDraggingOver(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      void processFiles(e.dataTransfer.files);
    }
  };

  const removeAttachment = (localId: string) => {
    const removed = attachmentsRef.current.find((attachment) => attachment.localId === localId);
    if (removed?.previewUrl) URL.revokeObjectURL(removed.previewUrl);
    setAttachments((prev) => prev.filter((a) => a.localId !== localId));
  };

  const handleSend = async () => {
    if (isSending || attachments.some((attachment) => attachment.uploading)) return;
    const trimmed = content.trim();
    const readyAttachments: Attachment[] = attachments
      .filter((a) => !a.uploading && !a.error && a.uploaded)
      .map((a) => a.uploaded!);

    if (!trimmed && readyAttachments.length === 0) return;

    setSendError(null);
    setIsSending(true);
    // If editing an existing message
    if (editingMessage) {
      try {
        if (trimmed) {
          await editMessage(editingMessage.id, trimmed);
        }
        setEditingMessage(null);
        setContent((current) => current === content ? '' : current);
      } catch (error) {
        setSendError(userFacingError(error, { locale, action: 'message action' }));
      } finally {
        setIsSending(false);
      }
      return;
    }

    const sentAttachmentIds = new Set(
      attachments.filter((item) => !item.error && item.uploaded).map((item) => item.localId),
    );

    try {
      notifyTyping(false);
    } catch {
      // ignore
    }

    try {
      if (effectiveMode === 'dm') {
        await sendDmMessage(trimmed, readyAttachments.length > 0 ? readyAttachments : undefined);
      } else {
        await sendMessage(trimmed, readyAttachments.length > 0 ? readyAttachments : undefined);
      }
      attachments
        .filter((item) => sentAttachmentIds.has(item.localId) && item.previewUrl)
        .forEach((item) => URL.revokeObjectURL(item.previewUrl!));
      setContent((current) => current === content ? '' : current);
      setAttachments((current) => current.filter((item) => !sentAttachmentIds.has(item.localId)));
      setMentionQuery(null);
    } catch (error) {
      setSendError(userFacingError(error, { locale, action: 'message action' }));
    } finally {
      setIsSending(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (isComposingRef.current || e.nativeEvent.isComposing) {
      return;
    }

    if (mentionQuery !== null && mentionCandidates.length > 0) {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setMentionIndex((prev) => (prev + 1) % mentionCandidates.length);
        return;
      }
      if (e.key === 'ArrowUp') {
        e.preventDefault();
        setMentionIndex(
          (prev) =>
            (prev - 1 + mentionCandidates.length) % mentionCandidates.length
        );
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        insertMention(mentionCandidates[mentionIndex].username);
        return;
      }
      if (e.key === 'Escape') {
        setMentionQuery(null);
        return;
      }
    }

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (attachments.some((attachment) => attachment.uploading) || isSending) return;
      void handleSend();
    } else if (e.key === 'Escape') {
      if (editingMessage) {
        setEditingMessage(null);
        setContent('');
      } else if (replyingTo) {
        setReplyingTo(null);
      }
    }
  };

  const defaultPlaceholder =
    placeholder ||
    (effectiveMode === 'dm'
      ? 'Nhắn tin riêng...'
      : `Nhắn tin trong #${activeChannel?.name || 'kênh này'}...`);

  // Normalize typingUsers list
  const activeTypers: string[] = (Array.isArray(typingUsers)
    ? typingUsers
    : []
  )
    .filter((u) => !currentUser || u.userId !== currentUser.id)
    .map((u) => u.displayName)
    .filter(Boolean);

  const canSend =
    content.trim().length > 0 ||
    attachments.filter((a) => !a.uploading && a.uploaded).length > 0;
  const uploadsPending = attachments.some((attachment) => attachment.uploading);

  return (
    <div
      onDragOver={(e) => {
        e.preventDefault();
        setIsDraggingOver(true);
      }}
      onDragLeave={() => setIsDraggingOver(false)}
      onDrop={handleDrop}
      className="relative px-4 pt-2 pb-3 bg-white/95 dark:bg-[#0f172a]/95 border-t border-slate-200/80 dark:border-slate-800/80 select-none shrink-0"
    >
      {/* Drag & Drop Overlay in Cliq Royal Blue */}
      {isDraggingOver && (
        <div className="pointer-events-none absolute inset-x-4 inset-y-1.5 z-30 flex items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-[#2563eb] bg-blue-50/95 dark:bg-slate-900/95 text-sm font-bold text-[#2563eb] dark:text-blue-400 shadow-lg">
          <Paperclip className="h-5 w-5 animate-bounce" />
          <span>
            Thả tệp hoặc hình ảnh (≤ 5 MB) vào đây để gửi
          </span>
        </div>
      )}

      {/* @MENTION AUTOCOMPLETE POPUP */}
      {mentionQuery !== null && mentionCandidates.length > 0 && (
        <div className="absolute bottom-full left-4 z-40 mb-1.5 w-72 overflow-hidden rounded-2xl border border-slate-200/90 dark:border-slate-700/80 bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 shadow-xl">
          <div className="border-b border-slate-200/80 dark:border-slate-700/80 px-3 py-1.5 text-[10px] font-bold tracking-wider uppercase text-slate-500 dark:text-slate-400">
            Nhắc đến thành viên
          </div>
          <div className="p-1.5 space-y-0.5">
            {mentionCandidates.map((cand, idx) => {
              const roleLabel = getMentionRoleLabel(cand.role);
              return (
              <button
                key={cand.username}
                type="button"
                onClick={() => insertMention(cand.username)}
                className={`flex w-full items-center justify-between rounded-xl px-2.5 py-1.5 text-left text-xs transition cursor-pointer ${
                  idx === mentionIndex
                    ? 'bg-[#2563eb] text-white shadow-2xs'
                    : 'hover:bg-slate-100 dark:hover:bg-slate-700/70 text-slate-700 dark:text-slate-200'
                }`}
              >
                <div className="flex items-center gap-2 truncate">
                  <AtSign className="h-3.5 w-3.5 shrink-0 opacity-80" />
                  <span className="font-bold">@{cand.username}</span>
                  <span className="truncate opacity-80">
                    {cand.displayName}
                  </span>
                </div>
                {roleLabel && (
                  <span className="ml-2 rounded-md px-1.5 py-0.5 font-mono text-[9px] font-bold uppercase opacity-80">
                    {roleLabel}
                  </span>
                )}
              </button>
              );
            })}
          </div>
        </div>
      )}

      {/* COMPOSER CONTAINER (Zoho Cliq Style) */}
      <div className="relative rounded-2xl bg-slate-50/90 dark:bg-slate-800/75 border border-slate-200/90 dark:border-slate-700/80 focus-within:border-blue-500/70 focus-within:bg-white dark:focus-within:bg-slate-800 focus-within:ring-3 focus-within:ring-blue-500/10 transition-all shadow-2xs overflow-hidden">
        {/* REPLYING TO / EDITING MESSAGE BANNER (Cliq Blue Accent Bar) */}
        {(replyingTo || editingMessage) && (
          <div className="flex items-center justify-between border-b border-slate-200/80 dark:border-slate-700/70 border-l-3 border-l-blue-600 bg-blue-50/70 dark:bg-blue-950/35 text-slate-700 dark:text-slate-200 px-3.5 py-2 text-xs">
            <div className="flex min-w-0 items-center gap-2">
              {editingMessage ? (
                <>
                  <Edit3 className="h-3.5 w-3.5 shrink-0 text-[#2563eb] dark:text-blue-400" />
                  <span className="font-bold text-[#2563eb] dark:text-blue-400">Đang chỉnh sửa:</span>
                  <span className="truncate opacity-85">{editingMessage.content}</span>
                </>
              ) : (
                replyingTo && (
                  <>
                    <Reply className="h-3.5 w-3.5 shrink-0 text-[#2563eb] dark:text-blue-400" />
                    <span>
                      Đang trả lời{' '}
                      <strong className="font-bold text-[#2563eb] dark:text-blue-400">
                        @{replyingTo.senderName}
                      </strong>
                      :
                    </span>
                    <span className="truncate opacity-85">{replyingTo.content}</span>
                  </>
                )
              )}
            </div>

            <button
              type="button"
              onClick={() => {
                if (editingMessage) {
                  setEditingMessage(null);
                  setContent('');
                } else {
                  setReplyingTo(null);
                }
              }}
              aria-label="Hủy trả lời hoặc chỉnh sửa"
              className="ml-2 rounded-lg p-1 text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-white hover:bg-blue-100/60 dark:hover:bg-slate-700/60 transition-colors cursor-pointer"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        )}

        {/* FILE ERROR ALERT */}
        {(fileError || sendError) && (
          <div className="flex items-center justify-between border-b border-rose-200 dark:border-rose-900/60 bg-rose-50/90 dark:bg-rose-950/40 px-4 py-1.5 text-xs font-semibold text-rose-600 dark:text-rose-400">
            <span className="flex items-center gap-1.5">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" />
              <span>{sendError || fileError}</span>
            </span>
            <button
              type="button"
              onClick={() => { setFileError(null); setSendError(null); }}
              className="rounded p-0.5 hover:bg-rose-500/10 cursor-pointer"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        )}

        {/* ATTACHMENT THUMBNAIL STRIP */}
        {attachments.length > 0 && (
          <div className="flex flex-wrap gap-2.5 border-b border-slate-200/80 dark:border-slate-700/70 px-3.5 py-2.5">
            {attachments.map((item) => (
              <div
                key={item.localId}
                className="group relative flex items-center gap-2.5 rounded-xl border border-slate-200/90 dark:border-slate-700 bg-white dark:bg-slate-900 p-2 pr-7 text-xs shadow-2xs"
              >
                {item.previewUrl ? (
                  <img
                    src={item.previewUrl}
                    alt={item.file.name}
                    className="h-10 w-10 rounded-lg object-cover"
                  />
                ) : (
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-blue-50 dark:bg-blue-950/60 text-[#2563eb] dark:text-blue-400">
                    <FileText className="h-5 w-5" />
                  </div>
                )}

                <div className="max-w-[140px] min-w-0">
                  <div className="truncate font-bold text-slate-800 dark:text-slate-100">
                    {item.file.name}
                  </div>
                  <div className="font-mono text-[10px] text-slate-500 dark:text-slate-400 tabular-nums">
                    {(item.file.size / 1024).toFixed(1)} KB
                  </div>
                  {item.uploading && (
                    <div className="mt-1 h-1 w-full overflow-hidden rounded-full bg-slate-200 dark:bg-slate-700">
                      <div className="h-full w-2/3 animate-pulse bg-[#2563eb]" />
                    </div>
                  )}
                  {item.error && (
                    <div className="text-[10px] font-bold text-rose-500">
                      {item.error}
                    </div>
                  )}
                </div>

                <button
                  type="button"
                  onClick={() => removeAttachment(item.localId)}
                  aria-label="Xóa tệp đính kèm"
                  className="absolute top-1.5 right-1.5 rounded-full bg-slate-200 dark:bg-slate-700 hover:bg-rose-500 hover:text-white p-0.5 text-slate-600 dark:text-slate-300 shadow-2xs transition-colors cursor-pointer"
                >
                  <X className="h-3 w-3" />
                </button>
              </div>
            ))}
          </div>
        )}

        {/* VOICE RECORDING ACTIVE BAR OR MAIN INPUT ROW */}
        {isRecordingVoice ? (
          <div className="flex items-center justify-between gap-3 px-3.5 py-2.5 bg-blue-50/70 dark:bg-blue-950/30">
            <div className="flex items-center gap-2.5">
              <span className="h-2.5 w-2.5 rounded-full bg-rose-500 animate-ping" />
              <span className="font-mono text-xs font-bold text-slate-800 dark:text-slate-100 tabular-nums">
                Đang ghi âm: {formatRecordingDuration(recordingSeconds)}
              </span>
            </div>

            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={handleCancelVoiceRecording}
                title="Hủy ghi âm"
                aria-label="Hủy ghi âm"
                className="inline-flex items-center gap-1 rounded-xl px-2.5 py-1.5 text-xs font-bold text-rose-600 dark:text-rose-400 hover:bg-rose-100/70 dark:hover:bg-rose-950/50 transition-colors cursor-pointer"
              >
                <Trash2 className="h-3.5 w-3.5" />
                <span>Hủy</span>
              </button>
              <button
                type="button"
                onClick={() => void handleFinishVoiceRecording()}
                title="Gửi ghi âm"
                aria-label="Gửi ghi âm"
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#2563eb] hover:bg-[#1d4ed8] px-3 py-1.5 text-xs font-bold text-white shadow-xs transition-colors cursor-pointer"
              >
                <Square className="h-3 w-3 fill-current" />
                <span>Gửi clip thoại</span>
              </button>
            </div>
          </div>
        ) : (
          <div className="flex items-center gap-1.5 px-2.5 py-1.5">
            {/* Hidden File Input */}
            <input
              ref={fileInputRef}
              type="file"
              multiple
              onChange={(e) => {
                if (e.target.files && e.target.files.length > 0) {
                  void processFiles(e.target.files);
                  e.target.value = '';
                }
              }}
              className="hidden"
            />

            {/* Attachment Paperclip Button */}
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              title="Đính kèm tệp hoặc hình ảnh (Tối đa 5 MB)"
              aria-label="Đính kèm tệp hoặc hình ảnh"
              className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl text-slate-500 dark:text-slate-400 hover:text-[#2563eb] dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-slate-700/70 transition-colors cursor-pointer"
            >
              <Paperclip className="h-4 w-4" />
            </button>

            {/* Voice Clip Recording Button */}
            <button
              type="button"
              onClick={() => void handleStartVoiceRecording()}
              title="Ghi âm tin nhắn thoại"
              aria-label="Ghi âm tin nhắn thoại"
              className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl text-slate-500 dark:text-slate-400 hover:text-[#2563eb] dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-slate-700/70 transition-colors cursor-pointer"
            >
              <Mic className="h-4 w-4" />
            </button>

            {/* Auto-expanding Textarea */}
            <textarea
              ref={textareaRef}
              rows={1}
              value={content}
              onChange={handleTextChange}
              onKeyDown={handleKeyDown}
              onPaste={handlePaste}
              onCompositionStart={() => {
                isComposingRef.current = true;
              }}
              onCompositionEnd={() => {
                isComposingRef.current = false;
              }}
              placeholder={defaultPlaceholder}
              className="max-h-[140px] min-h-[34px] flex-1 resize-none bg-transparent px-1.5 py-1.5 text-[13.5px] leading-relaxed font-medium text-slate-900 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500 outline-none"
            />

            {/* Quick Emoji Picker Button */}
            <div className="relative">
              <button
                type="button"
                onClick={() => setIsEmojiPickerOpen(!isEmojiPickerOpen)}
                title="Chèn biểu tượng cảm xúc"
                aria-label="Chèn biểu tượng cảm xúc"
                className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-xl transition-colors cursor-pointer ${
                  isEmojiPickerOpen
                    ? 'bg-blue-50 dark:bg-blue-950/60 text-[#2563eb] dark:text-blue-400'
                    : 'text-slate-500 dark:text-slate-400 hover:text-[#2563eb] dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-slate-700/70'
                }`}
              >
                <Smile className="h-4 w-4" />
              </button>

              {isEmojiPickerOpen && (
                <div className="absolute right-0 bottom-11 z-40 flex items-center gap-1 rounded-2xl border border-slate-200/90 dark:border-slate-700 bg-white dark:bg-slate-800 p-1.5 shadow-xl">
                  {QUICK_EMOJIS.map((emoji) => (
                    <button
                      key={emoji}
                      type="button"
                      onClick={() => {
                        setContent((prev) => `${prev}${emoji}`);
                        setIsEmojiPickerOpen(false);
                        textareaRef.current?.focus();
                      }}
                      className="flex h-8 w-8 items-center justify-center rounded-xl text-base transition hover:scale-110 hover:bg-blue-50 dark:hover:bg-slate-700 cursor-pointer"
                    >
                      {emoji}
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Cliq Primary Blue Send Button */}
            <button
              type="button"
              onClick={() => void handleSend()}
              disabled={!canSend || uploadsPending || isSending}
              aria-busy={isSending}
              aria-label="Gửi tin nhắn"
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-[#2563eb] hover:bg-[#1d4ed8] text-white shadow-xs transition-all active:scale-95 disabled:bg-slate-200 dark:disabled:bg-slate-700/80 disabled:text-slate-400 dark:disabled:text-slate-500 disabled:shadow-none disabled:scale-100 cursor-pointer disabled:cursor-not-allowed"
            >
              <Send className="h-4 w-4" />
            </button>
          </div>
        )}
      </div>

      {/* Typing indicator and keyboard hint */}
      <div className="mt-1.5 flex h-4 items-center justify-between px-1 text-[11px]">
        <div className="flex items-center gap-1.5">
          {activeTypers.length > 0 ? (
            <div className="flex items-center gap-1.5 font-semibold text-[#2563eb] dark:text-blue-400">
              <span className="inline-flex items-center gap-0.5">
                <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#2563eb] dark:bg-blue-400" />
                <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#2563eb] dark:bg-blue-400 [animation-delay:150ms]" />
                <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#2563eb] dark:bg-blue-400 [animation-delay:300ms]" />
              </span>
              <span>
                {activeTypers.join(', ')} đang nhập...
              </span>
            </div>
          ) : (
            <span aria-hidden="true" />
          )}
        </div>

        <div className="hidden items-center gap-2 text-[10px] text-slate-400 dark:text-slate-500 md:flex">
          <span>
            <strong>Enter</strong> để gửi
          </span>
          <span>•</span>
          <span>
            <strong>Shift+Enter</strong> xuống dòng
          </span>
          <span>•</span>
          <span className="inline-flex items-center gap-0.5">
            <ImageIcon className="h-3 w-3" /> Dán ảnh <strong>Ctrl+V</strong>
          </span>
        </div>
      </div>
    </div>
  );
};

export default MessageComposer;
