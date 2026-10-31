import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ChevronDown,
  ChevronUp,
  HeadphoneOff,
  Headphones,
  Mic,
  MicOff,
  Monitor,
  MonitorOff,
  PhoneOff,
  RefreshCw,
  Sparkles,
  Video,
  VideoOff,
  Volume2,
  Wifi,
} from 'lucide-react';
import { useAppStore } from '../../shared/state/appStore';
import type { VoiceParticipant } from '../../shared/types/api';

const STUN_CONFIG: RTCConfiguration = {
  iceServers: [{ urls: 'stun:stun.l.google.com:19302' }],
};

const syncPeerSenders = async (
  peerConnections: Map<number, RTCPeerConnection>,
  localStream: MediaStream | null,
  screenTrack: MediaStreamTrack | null,
) => {
  const tracksByKind: Record<'audio' | 'video', MediaStreamTrack | null> = {
    audio: localStream?.getAudioTracks()[0] ?? null,
    video: screenTrack ?? localStream?.getVideoTracks()[0] ?? null,
  };

  for (const pc of peerConnections.values()) {
    for (const kind of ['audio', 'video'] as const) {
      let transceiver = pc.getTransceivers().find((item) => item.receiver.track.kind === kind);
      if (!transceiver) transceiver = pc.addTransceiver(kind, { direction: 'sendrecv' });
      else if (transceiver.direction !== 'sendrecv') transceiver.direction = 'sendrecv';
      await transceiver.sender.replaceTrack(tracksByKind[kind]);
    }
  }
};

export const VoiceCallStage: React.FC = () => {
  const {
    currentUser,
    theme,
    guilds,
    activeVoiceChannelId,
    voiceParticipants,
    voiceSignals,
    localVoiceState,
    updateLocalVoiceState,
    leaveVoiceChannel,
    sendVoiceSignal,
    consumeVoiceSignal,
  } = useAppStore();

  const isDark = theme === 'dark';

  // Stage UI state
  const [isCompactBar, setIsCompactBar] = useState(false);
  const [mediaNotice, setMediaNotice] = useState<string | null>(null);
  const [meshHandshakes, setMeshHandshakes] = useState<Record<number, { iceState: string }>>({});
  const [remoteStreams, setRemoteStreams] = useState<Record<number, MediaStream>>({});

  // Media Stream & Video Element Refs
  const localVideoRef = useRef<HTMLVideoElement | null>(null);
  const screenVideoRef = useRef<HTMLVideoElement | null>(null);
  const localStreamRef = useRef<MediaStream | null>(null);
  const screenStreamRef = useRef<MediaStream | null>(null);
  const peerConnectionsRef = useRef<Map<number, RTCPeerConnection>>(new Map());
  const pendingIceCandidatesRef = useRef<Map<number, RTCIceCandidateInit[]>>(new Map());

  // Resolve current voice channel name
  const activeVoiceChannel = useMemo(() => {
    if (activeVoiceChannelId === null) return null;
    for (const g of guilds) {
      const found = g.channels.find((c) => c.id === activeVoiceChannelId);
      if (found) return found;
    }
    return null;
  }, [guilds, activeVoiceChannelId]);

  const meshParticipants = useMemo(
    () => voiceParticipants.filter((participant) => participant.channelId === activeVoiceChannelId),
    [activeVoiceChannelId, voiceParticipants],
  );

  // Create/manage RTCPeerConnection mesh for a given target peer & emit real WebRTC signaling frames
  const initiatePeerHandshake = useCallback(
    async (targetPeer: VoiceParticipant) => {
      if (activeVoiceChannelId === null) return;
      const targetUserId = targetPeer.userId;

      try {
        // Close existing connection if re-negotiating
        const existing = peerConnectionsRef.current.get(targetUserId);
        if (existing) {
          existing.close();
        }

        const pc = new RTCPeerConnection(STUN_CONFIG);
        peerConnectionsRef.current.set(targetUserId, pc);
        pc.oniceconnectionstatechange = () => {
          setMeshHandshakes((prev) => ({
            ...prev,
            [targetUserId]: { iceState: pc.iceConnectionState.toUpperCase() },
          }));
        };
        pc.ontrack = (event) => {
          const stream = event.streams[0] ?? new MediaStream([event.track]);
          setRemoteStreams((prev) => ({ ...prev, [targetUserId]: stream }));
        };

        // Keep sendrecv slots negotiated while capture starts; replaceTrack can
        // attach media later without renegotiating the peer connection.
        pc.addTransceiver('audio', { direction: 'sendrecv' });
        pc.addTransceiver('video', { direction: 'sendrecv' });
        await syncPeerSenders(
          peerConnectionsRef.current,
          localStreamRef.current,
          screenStreamRef.current?.getVideoTracks()[0] ?? null,
        );

        pc.onicecandidate = (ev) => {
          if (ev.candidate) {
            if (!sendVoiceSignal('SIGNAL_ICE', targetUserId, { candidate: ev.candidate.toJSON() })) {
              setMediaNotice('Mất kết nối phòng thoại. Hãy thử rời phòng rồi vào lại.');
            }
          }
        };

        const offer = await pc.createOffer({
          offerToReceiveAudio: true,
          offerToReceiveVideo: true,
        });
        await pc.setLocalDescription(offer);

        if (!sendVoiceSignal('SIGNAL_OFFER', targetUserId, { sdp: pc.localDescription?.toJSON() ?? offer })) {
          setMediaNotice('Mất kết nối phòng thoại. Hãy thử rời phòng rồi vào lại.');
        }

      } catch {
        setMediaNotice('Không thể kết nối cuộc gọi. Vui lòng thử lại.');
      }
    },
    [activeVoiceChannelId, sendVoiceSignal]
  );

  useEffect(() => {
    let cancelled = false;
    const processSignals = async () => {
      for (const signal of voiceSignals) {
        const fromUserId = Number(signal.payload.fromUserId);
        const channelId = Number(signal.payload.channelId ?? signal.payload.roomId);
        if (channelId !== activeVoiceChannelId || !Number.isSafeInteger(fromUserId) || fromUserId <= 0 || fromUserId === currentUser?.id) {
          consumeVoiceSignal(signal.id);
          continue;
        }
        try {
          let pc = peerConnectionsRef.current.get(fromUserId);
          if (signal.type === 'SIGNAL_OFFER') {
            if (!pc) {
              pc = new RTCPeerConnection(STUN_CONFIG);
              peerConnectionsRef.current.set(fromUserId, pc);
              pc.oniceconnectionstatechange = () => setMeshHandshakes((prev) => ({
                ...prev,
                [fromUserId]: { iceState: pc!.iceConnectionState.toUpperCase() },
              }));
              pc.ontrack = (event) => {
                const stream = event.streams[0] ?? new MediaStream([event.track]);
                setRemoteStreams((prev) => ({ ...prev, [fromUserId]: stream }));
              };
              pc.onicecandidate = (event) => {
                if (event.candidate && !sendVoiceSignal('SIGNAL_ICE', fromUserId, { candidate: event.candidate.toJSON() })) {
                  setMediaNotice('Mất kết nối phòng thoại. Hãy thử rời phòng rồi vào lại.');
                }
              };
              pc.addTransceiver('audio', { direction: 'sendrecv' });
              pc.addTransceiver('video', { direction: 'sendrecv' });
            }
            await syncPeerSenders(
              peerConnectionsRef.current,
              localStreamRef.current,
              screenStreamRef.current?.getVideoTracks()[0] ?? null,
            );
            const sdp = signal.payload.sdp as RTCSessionDescriptionInit | undefined;
            if (!sdp?.type || !sdp.sdp) throw new Error('Offer did not include a valid session description.');
            await pc.setRemoteDescription(sdp);
            const pendingCandidates = pendingIceCandidatesRef.current.get(fromUserId) ?? [];
            pendingIceCandidatesRef.current.delete(fromUserId);
            for (const candidate of pendingCandidates) await pc.addIceCandidate(candidate);
            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);
            if (!sendVoiceSignal('SIGNAL_ANSWER', fromUserId, { sdp: pc.localDescription?.toJSON() ?? answer })) {
              setMediaNotice('Mất kết nối phòng thoại. Hãy thử rời phòng rồi vào lại.');
            }
          } else if (signal.type === 'SIGNAL_ANSWER') {
            if (!pc) throw new Error('Received an answer without a matching peer connection.');
            const sdp = signal.payload.sdp as RTCSessionDescriptionInit | undefined;
            if (!sdp?.type || !sdp.sdp) throw new Error('Answer did not include a valid session description.');
            await pc.setRemoteDescription(sdp);
            const pendingCandidates = pendingIceCandidatesRef.current.get(fromUserId) ?? [];
            pendingIceCandidatesRef.current.delete(fromUserId);
            for (const candidate of pendingCandidates) await pc.addIceCandidate(candidate);
          } else {
            const candidate = signal.payload.candidate as RTCIceCandidateInit | undefined;
            if (!candidate?.candidate) throw new Error('ICE event did not include a valid candidate.');
            if (!pc || !pc.remoteDescription) {
              const pending = pendingIceCandidatesRef.current.get(fromUserId) ?? [];
              pendingIceCandidatesRef.current.set(fromUserId, [...pending, candidate]);
            } else {
              await pc.addIceCandidate(candidate);
            }
          }
          if (!cancelled) consumeVoiceSignal(signal.id);
        } catch {
          if (!cancelled) {
            setMediaNotice('Không thể kết nối cuộc gọi. Vui lòng thử lại.');
            consumeVoiceSignal(signal.id);
          }
        }
      }
    };
    void processSignals();
    return () => { cancelled = true; };
  }, [activeVoiceChannelId, consumeVoiceSignal, currentUser?.id, sendVoiceSignal, voiceSignals]);

  // Trigger initial WebRTC signaling handshake for peers when joining voice channel
  useEffect(() => {
    if (activeVoiceChannelId === null) {
      // Cleanup all RTCPeerConnections and media streams when leaving voice channel
      peerConnectionsRef.current.forEach((pc) => pc.close());
      peerConnectionsRef.current.clear();
      setRemoteStreams({});
      if (localStreamRef.current) {
        localStreamRef.current.getTracks().forEach((t) => t.stop());
        localStreamRef.current = null;
      }
      if (screenStreamRef.current) {
        screenStreamRef.current.getTracks().forEach((t) => t.stop());
        screenStreamRef.current = null;
      }
      return;
    }

    if (!currentUser) return;
    const meId = currentUser.id;
    meshParticipants.forEach((peer) => {
      if (peer.userId > meId && !peerConnectionsRef.current.has(peer.userId)) {
        void initiatePeerHandshake(peer);
      }
    });
  }, [activeVoiceChannelId, meshParticipants, currentUser, initiatePeerHandshake]);

  useEffect(() => {
    const participantIds = new Set(meshParticipants.map((participant) => participant.userId));
    for (const [userId, pc] of peerConnectionsRef.current) {
      if (!participantIds.has(userId)) {
        pc.close();
        peerConnectionsRef.current.delete(userId);
        setRemoteStreams((prev) => {
          const next = { ...prev };
          delete next[userId];
          return next;
        });
      }
    }
  }, [meshParticipants]);

  // Handle Camera & Microphone Hardware Stream via navigator.mediaDevices.getUserMedia
  useEffect(() => {
    if (activeVoiceChannelId === null) return undefined;

    let cancelled = false;

    const syncLocalMedia = async () => {
      const wantsVideo = Boolean(localVoiceState.cameraOn);
      const wantsAudio = !localVoiceState.muted && !localVoiceState.deafened;

      if (!wantsAudio && !wantsVideo) {
        localStreamRef.current?.getTracks().forEach((track) => track.stop());
        localStreamRef.current = null;
        if (localVideoRef.current) localVideoRef.current.srcObject = null;
        void syncPeerSenders(
          peerConnectionsRef.current,
          null,
          screenStreamRef.current?.getVideoTracks()[0] ?? null,
        ).catch(() => setMediaNotice('KhÃ´ng thá»ƒ cáº­p nháº­t micro hoáº·c mÃ¡y áº£nh cho cuá»™c gá»i.'));
        return;
      }

      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        setMediaNotice('Thiết bị này chưa hỗ trợ gọi thoại hoặc video.');
        return;
      }

      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: wantsVideo ? { width: { ideal: 640 }, height: { ideal: 360 } } : false,
          audio: wantsAudio,
        });
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        localStreamRef.current?.getTracks().forEach((track) => track.stop());
        localStreamRef.current = stream;
        if (localVideoRef.current) {
          localVideoRef.current.srcObject = stream;
        }
        await syncPeerSenders(
          peerConnectionsRef.current,
          stream,
          screenStreamRef.current?.getVideoTracks()[0] ?? null,
        );
        setMediaNotice(null);
      } catch {
        setMediaNotice('Không thể bật micro hoặc máy ảnh. Hãy kiểm tra quyền truy cập trên trình duyệt.');
      }
    };

    void syncLocalMedia();

    return () => {
      cancelled = true;
    };
  }, [
    activeVoiceChannelId,
    localVoiceState.cameraOn,
    localVoiceState.muted,
    localVoiceState.deafened,
  ]);

  const stopScreenShare = useCallback(async () => {
    const screenStream = screenStreamRef.current;
    screenStreamRef.current = null;
    if (screenVideoRef.current) screenVideoRef.current.srcObject = null;
    updateLocalVoiceState({ screenSharing: false });
    try {
      await syncPeerSenders(peerConnectionsRef.current, localStreamRef.current, null);
    } catch {
      setMediaNotice('Chưa thể khôi phục hình ảnh từ máy ảnh trong cuộc gọi.');
    } finally {
      screenStream?.getTracks().forEach((track) => track.stop());
    }
  }, [updateLocalVoiceState]);

  const handleToggleScreenShare = async () => {
    if (localVoiceState.screenSharing) {
      await stopScreenShare();
      return;
    }
    if (!navigator.mediaDevices?.getDisplayMedia) {
      setMediaNotice('Thiết bị này chưa hỗ trợ chia sẻ màn hình.');
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({ video: true });
      if (activeVoiceChannelId === null) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      screenStreamRef.current = stream;
      if (screenVideoRef.current) screenVideoRef.current.srcObject = stream;
      stream.getVideoTracks()[0]?.addEventListener('ended', () => { void stopScreenShare(); }, { once: true });
      await syncPeerSenders(peerConnectionsRef.current, localStreamRef.current, stream.getVideoTracks()[0] ?? null);
      updateLocalVoiceState({ screenSharing: true });
    } catch {
      screenStreamRef.current?.getTracks().forEach((track) => track.stop());
      screenStreamRef.current = null;
      setMediaNotice('Không thể chia sẻ màn hình. Hãy kiểm tra quyền truy cập trên trình duyệt.');
    }
  };

  if (activeVoiceChannelId === null) {
    return null;
  }

  const stageBg = isDark ? '#0f172a' : '#f8fafc';
  const tileBg = isDark ? '#1e293b' : '#ffffff';
  const borderColor = isDark ? '#1e293b' : '#e2e8f0';
  const textPrimary = isDark ? '#f8fafc' : '#0f172a';
  const textSecondary = isDark ? '#cbd5e1' : '#334155';
  const textMuted = isDark ? '#94a3b8' : '#64748b';
  const callStatusLabel = (status?: string) => {
    switch (status) {
      case 'CONNECTED':
      case 'COMPLETED':
        return 'Đã kết nối';
      case 'FAILED':
        return 'Không thể kết nối';
      case 'DISCONNECTED':
        return 'Mất kết nối';
      case 'CLOSED':
        return 'Đã rời phòng';
      default:
        return 'Đang kết nối';
    }
  };

  return (
    <section
      aria-label="Phòng thoại Uri"
      style={{
        fontFamily: 'var(--font-sans)',
        backgroundColor: stageBg,
        color: textPrimary,
        borderBottom: `1px solid ${borderColor}`,
        padding: isCompactBar ? '8px 18px' : '14px 18px',
        display: 'flex',
        flexDirection: 'column',
        gap: '10px',
        flexShrink: 0,
        transition: 'all 180ms ease',
      }}
    >
      {Object.entries(remoteStreams).map(([userId, stream]) => (
        <audio
          key={userId}
          autoPlay
          playsInline
          ref={(audio) => {
            if (audio) {
              audio.srcObject = stream;
              audio.muted = localVoiceState.deafened;
            }
          }}
          aria-label="Âm thanh từ người tham gia"
        />
      ))}
      {/* Top Voice Stage Header */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          flexWrap: 'wrap',
          gap: '8px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div
            style={{
              width: '36px',
              height: '36px',
              borderRadius: '12px',
              backgroundColor: isDark ? 'rgba(37, 99, 235, 0.2)' : '#eff6ff',
              color: isDark ? '#60a5fa' : '#2563eb',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              border: `1px solid ${isDark ? 'rgba(59, 130, 246, 0.35)' : '#bfdbfe'}`,
            }}
          >
            <Volume2 size={18} />
          </div>

          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
              <span style={{ fontWeight: 700, fontSize: '14.5px' }}>
                {activeVoiceChannel?.name || `Phòng Thoại Uri #${activeVoiceChannelId}`}
              </span>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
          <button
            type="button"
            onClick={() => setIsCompactBar(!isCompactBar)}
            style={{
              width: '32px',
              height: '32px',
              borderRadius: '10px',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: tileBg,
              color: textSecondary,
              border: `1px solid ${borderColor}`,
              cursor: 'pointer',
            }}
            title={isCompactBar ? 'Mở rộng sân khấu Video' : 'Thu gọn thành thanh nổi'}
            aria-label={isCompactBar ? 'Mở rộng sân khấu Video' : 'Thu gọn thành thanh nổi'}
          >
            {isCompactBar ? <ChevronDown size={15} /> : <ChevronUp size={15} />}
          </button>
        </div>
      </div>

      {/* Graceful Hardware Fallback Info Notice */}
      {!isCompactBar && mediaNotice && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: '8px',
            padding: '6px 12px',
            borderRadius: '8px',
            backgroundColor: isDark ? 'rgba(2, 132, 199, 0.16)' : '#E0F2FE',
            border: '1px solid rgba(2, 132, 199, 0.35)',
            color: isDark ? '#38BDF8' : '#0369A1',
            fontSize: '12px',
            fontWeight: 600,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <Sparkles size={14} />
            <span>{mediaNotice}</span>
          </div>
          <button
            type="button"
            onClick={() => setMediaNotice(null)}
            style={{ fontSize: '11px', fontWeight: 700, textDecoration: 'underline' }}
          >
            Ẩn
          </button>
        </div>
      )}

      {/* Expanded WebRTC P2P Mesh Video / Audio-Wave Grid (<= 4 Participants) */}
      {!isCompactBar && (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns:
              meshParticipants.length === 1
                ? 'minmax(260px, 480px)'
                : 'repeat(auto-fit, minmax(230px, 1fr))',
            justifyContent: 'center',
            gap: '10px',
          }}
        >
          {meshParticipants.map((participant) => {
            const isMe = Boolean(currentUser && participant.userId === currentUser.id);
            const isSpeaking = Boolean(participant.speaking);
            const handshake = meshHandshakes[participant.userId];

            return (
              <div
                key={participant.userId}
                style={{
                  position: 'relative',
                  height: '168px',
                  borderRadius: '14px',
                  backgroundColor: tileBg,
                  border: isSpeaking
                    ? '2px solid #10B981'
                    : `1px solid ${borderColor}`,
                  boxShadow: isSpeaking
                    ? '0 0 0 3px rgba(16, 185, 129, 0.18)'
                    : '0 4px 12px rgba(15, 23, 42, 0.06)',
                  overflow: 'hidden',
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  justifyContent: 'center',
                  transition: 'border-color 150ms ease, box-shadow 150ms ease',
                }}
              >
                {/* Local Hardware Video Element when cameraOn is true */}
                {isMe && localVoiceState.cameraOn && (
                  <video
                    ref={localVideoRef}
                    autoPlay
                    playsInline
                    muted
                    style={{
                      position: 'absolute',
                      inset: 0,
                      width: '100%',
                      height: '100%',
                      objectFit: 'cover',
                      zIndex: 1,
                    }}
                  />
                )}

                {isMe && localVoiceState.screenSharing && (
                  <video
                    ref={screenVideoRef}
                    autoPlay
                    playsInline
                    muted
                    aria-label="Xem trước màn hình đang chia sẻ"
                    style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', zIndex: 2 }}
                  />
                )}

                {/* Audio-wave avatar tile for participants without active video */}
                <div
                  style={{
                    position: 'relative',
                    zIndex: 1,
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: '8px',
                  }}
                >
                  <div
                    style={{
                      position: 'relative',
                      width: '58px',
                      height: '58px',
                      borderRadius: '50%',
                      backgroundColor: participant.avatarColor || '#2563EB',
                      color: '#FFFFFF',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: '18px',
                      fontWeight: 700,
                      boxShadow: isSpeaking
                        ? '0 0 0 6px rgba(16, 185, 129, 0.24)'
                        : 'none',
                      transition: 'box-shadow 200ms ease, transform 200ms ease',
                      transform: isSpeaking ? 'scale(1.04)' : 'scale(1)',
                    }}
                  >
                    {(participant.displayName || participant.username || 'U')
                      .slice(0, 2)
                      .toUpperCase()}
                  </div>

                  {/* Animated 5-bar Audio Equalizer Wave */}
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'flex-end',
                      gap: '3px',
                      height: '14px',
                    }}
                    aria-hidden="true"
                  >
                    {[0, 1, 2, 3, 4].map((barIdx) => {
                      const activeHeight = isSpeaking ? 7 : 3;
                      return (
                        <span
                          key={barIdx}
                          style={{
                            width: '3px',
                            height: `${activeHeight}px`,
                            borderRadius: '9999px',
                            backgroundColor: isSpeaking ? '#10B981' : textMuted,
                            transition: 'height 160ms ease',
                          }}
                        />
                      );
                    })}
                  </div>
                </div>

                {/* Top-Left Protocol & ICE Telemetry Overlay Badge */}
                <div
                  style={{
                    position: 'absolute',
                    top: '8px',
                    left: '8px',
                    zIndex: 4,
                    display: 'flex',
                    alignItems: 'center',
                    gap: '4px',
                    padding: '2px 7px',
                    borderRadius: '6px',
                    backgroundColor: 'rgba(15, 23, 42, 0.72)',
                    color: '#F8FAFC',
                    fontFamily: 'var(--font-mono)',
                    fontSize: '10px',
                    fontWeight: 600,
                  }}
                >
                  <Wifi size={10} color="#10B981" />
                  <span>
                    {isMe
                      ? 'Bạn'
                      : callStatusLabel(handshake?.iceState)}
                  </span>
                </div>

                {/* Top-Right Re-negotiate SDP Trigger for Peer */}
                {!isMe && (
                  <button
                    type="button"
                    onClick={() => void initiatePeerHandshake(participant)}
                    style={{
                      position: 'absolute',
                      top: '8px',
                      right: '8px',
                      zIndex: 4,
                      padding: '2px 7px',
                      borderRadius: '6px',
                      backgroundColor: 'rgba(37, 99, 235, 0.85)',
                      color: '#FFFFFF',
                      fontFamily: 'var(--font-mono)',
                      fontSize: '10px',
                      fontWeight: 700,
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: '3px',
                    }}
                    title="Thử kết nối lại"
                    aria-label={`Thử kết nối lại với ${participant.displayName}`}
                  >
                    <RefreshCw size={10} />
                    <span>Thử lại</span>
                  </button>
                )}

                {/* Bottom Participant Name & Mic/Cam Status Pill */}
                <div
                  style={{
                    position: 'absolute',
                    bottom: '8px',
                    left: '8px',
                    right: '8px',
                    zIndex: 4,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '4px 8px',
                    borderRadius: '8px',
                    backgroundColor: 'rgba(15, 23, 42, 0.78)',
                    color: '#FFFFFF',
                    fontSize: '12px',
                    fontWeight: 600,
                  }}
                >
                  <span
                    style={{
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {participant.displayName}
                  </span>

                  <div style={{ display: 'flex', alignItems: 'center', gap: '5px', flexShrink: 0 }}>
                    {participant.cameraOn && (
                      <span
                        style={{
                          display: 'inline-flex',
                          padding: '2px 4px',
                          borderRadius: '4px',
                          backgroundColor: '#0284C7',
                          color: '#FFFFFF',
                        }}
                        title="Camera đang bật"
                      >
                        <Video size={11} />
                      </span>
                    )}
                    {participant.muted ? (
                      <span
                        style={{
                          display: 'inline-flex',
                          padding: '2px 4px',
                          borderRadius: '4px',
                          backgroundColor: '#F43F5E',
                          color: '#FFFFFF',
                        }}
                        title="Đã tắt Micro"
                      >
                        <MicOff size={11} />
                      </span>
                    ) : (
                      <span
                        style={{
                          display: 'inline-flex',
                          padding: '2px 4px',
                          borderRadius: '4px',
                          backgroundColor: '#10B981',
                          color: '#FFFFFF',
                        }}
                        title="Micro đang hoạt động"
                      >
                        <Mic size={11} />
                      </span>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Bottom Call Controls Bar */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexWrap: 'wrap',
          gap: '8px',
          paddingTop: '2px',
        }}
      >
        {/* 1. Mic Mute / Unmute */}
        <button
          type="button"
          onClick={() => updateLocalVoiceState({ muted: !localVoiceState.muted })}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '6px',
            height: '36px',
            padding: '0 14px',
            borderRadius: '10px',
            fontWeight: 700,
            fontSize: '12px',
            backgroundColor: localVoiceState.muted
              ? '#FFE4E6'
              : isDark
              ? '#1E293B'
              : '#FFFFFF',
            color: localVoiceState.muted ? '#F43F5E' : textPrimary,
            border: `1px solid ${localVoiceState.muted ? '#F43F5E' : borderColor}`,
          }}
        >
          {localVoiceState.muted ? <MicOff size={15} /> : <Mic size={15} color="#10B981" />}
          <span>{localVoiceState.muted ? 'Bật micro' : 'Tắt micro'}</span>
        </button>

        {/* 2. Headphone Deafen */}
        <button
          type="button"
          onClick={() => {
            const nextDeaf = !localVoiceState.deafened;
            updateLocalVoiceState({
              deafened: nextDeaf,
              muted: nextDeaf ? true : localVoiceState.muted,
            });
          }}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '6px',
            height: '36px',
            padding: '0 14px',
            borderRadius: '10px',
            fontWeight: 700,
            fontSize: '12px',
            backgroundColor: localVoiceState.deafened
              ? '#FFE4E6'
              : isDark
              ? '#1E293B'
              : '#FFFFFF',
            color: localVoiceState.deafened ? '#F43F5E' : textPrimary,
            border: `1px solid ${localVoiceState.deafened ? '#F43F5E' : borderColor}`,
          }}
        >
          {localVoiceState.deafened ? (
            <HeadphoneOff size={15} />
          ) : (
            <Headphones size={15} />
          )}
          <span>{localVoiceState.deafened ? 'Bật âm thanh' : 'Tắt âm thanh'}</span>
        </button>

        {/* 3. Camera On / Off */}
        <button
          type="button"
          onClick={() => updateLocalVoiceState({ cameraOn: !localVoiceState.cameraOn })}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '6px',
            height: '36px',
            padding: '0 14px',
            borderRadius: '10px',
            fontWeight: 700,
            fontSize: '12px',
            backgroundColor: localVoiceState.cameraOn
              ? '#2563EB'
              : isDark
              ? '#1E293B'
              : '#FFFFFF',
            color: localVoiceState.cameraOn ? '#FFFFFF' : textPrimary,
            border: `1px solid ${localVoiceState.cameraOn ? '#2563EB' : borderColor}`,
          }}
        >
          {localVoiceState.cameraOn ? <Video size={15} /> : <VideoOff size={15} />}
          <span>{localVoiceState.cameraOn ? 'Đang bật Camera' : 'Bật Camera'}</span>
        </button>

        {/* 4. Screen Share */}
        <button
          type="button"
          onClick={() => void handleToggleScreenShare()}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '6px',
            height: '36px',
            padding: '0 14px',
            borderRadius: '10px',
            fontWeight: 700,
            fontSize: '12px',
            backgroundColor:
              localVoiceState.screenSharing
                ? '#0284C7'
                : isDark
                ? '#1E293B'
                : '#FFFFFF',
            color:
              localVoiceState.screenSharing
                ? '#FFFFFF'
                : textPrimary,
            border: `1px solid ${
              localVoiceState.screenSharing
                ? '#0284C7'
                : borderColor
            }`,
          }}
        >
          {localVoiceState.screenSharing ? (
            <MonitorOff size={15} />
          ) : (
            <Monitor size={15} />
          )}
          <span>
            {localVoiceState.screenSharing
              ? 'Dừng Chia Sẻ Màn Hình'
              : 'Chia Sẻ Màn Hình'}
          </span>
        </button>

        {/* 5. Leave Voice Channel (Red #F43F5E Button) */}
        <button
          type="button"
          onClick={() => {
                        setMediaNotice(null);
            leaveVoiceChannel();
          }}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '6px',
            height: '36px',
            padding: '0 16px',
            borderRadius: '10px',
            fontWeight: 700,
            fontSize: '12px',
            backgroundColor: '#F43F5E',
            color: '#FFFFFF',
            boxShadow: '0 2px 8px rgba(244, 63, 94, 0.3)',
          }}
        >
          <PhoneOff size={15} />
          <span>Rời phòng thoại</span>
        </button>
      </div>
    </section>
  );
};

export default VoiceCallStage;
