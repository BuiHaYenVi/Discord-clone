package com.discordmini.server.web.ws;

import com.discordmini.server.model.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for WebRTC P2P Voice/Video lounge channels (up to 4 participants per room).
 */
public class VoiceRoomRegistry {

    public static final int MAX_PARTICIPANTS_PER_ROOM = 4;

    public static class VoiceParticipant {
        private final int userId;
        private final String username;
        private final String displayName;
        private final String avatarColor;
        private final int channelId;
        private final int roomId;
        private final transient String sessionId;
        private volatile boolean muted;
        private volatile boolean cameraOn;

        public VoiceParticipant(int userId, String username, String displayName, String avatarColor,
                                int channelId, boolean muted, boolean cameraOn) {
            this(userId, username, displayName, avatarColor, channelId, null, muted, cameraOn);
        }

        public VoiceParticipant(int userId, String username, String displayName, String avatarColor,
                                int channelId, String sessionId, boolean muted, boolean cameraOn) {
            this.userId = userId;
            this.username = username;
            this.displayName = displayName;
            this.avatarColor = avatarColor != null ? avatarColor : "#2563EB";
            this.channelId = channelId;
            this.roomId = channelId;
            this.sessionId = sessionId;
            this.muted = muted;
            this.cameraOn = cameraOn;
        }

        public int getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getDisplayName() { return displayName; }
        public String getAvatarColor() { return avatarColor; }
        public int getChannelId() { return channelId; }
        public int getRoomId() { return roomId; }
        public String getSessionId() { return sessionId; }
        public boolean isMuted() { return muted; }
        public void setMuted(boolean muted) { this.muted = muted; }
        public boolean isCameraOn() { return cameraOn; }
        public void setCameraOn(boolean cameraOn) { this.cameraOn = cameraOn; }
    }

    private final Map<Integer, Map<Integer, VoiceParticipant>> rooms = new ConcurrentHashMap<>();

    public synchronized List<VoiceParticipant> joinRoom(int channelId, User user, boolean muted, boolean cameraOn) {
        return joinRoom(channelId, user, null, muted, cameraOn);
    }

    public synchronized List<VoiceParticipant> joinRoom(int channelId, User user, String sessionId,
                                                         boolean muted, boolean cameraOn) {
        if (user == null || channelId <= 0) {
            return Collections.emptyList();
        }
        int userId = user.getId();

        // Remove user from any other voice rooms first
        for (Map.Entry<Integer, Map<Integer, VoiceParticipant>> entry : rooms.entrySet()) {
            if (entry.getKey() != channelId) {
                entry.getValue().remove(userId);
            }
        }

        Map<Integer, VoiceParticipant> participants = rooms.computeIfAbsent(
                channelId, k -> Collections.synchronizedMap(new LinkedHashMap<>())
        );

        if (!participants.containsKey(userId) && participants.size() >= MAX_PARTICIPANTS_PER_ROOM) {
            throw new IllegalStateException("Voice channel is full (maximum " + MAX_PARTICIPANTS_PER_ROOM + " participants).");
        }

        VoiceParticipant vp = new VoiceParticipant(
                userId,
                user.getUsername(),
                user.getDisplayName() != null ? user.getDisplayName() : user.getUsername(),
                user.getAvatarColor(),
                channelId,
                sessionId,
                muted,
                cameraOn
        );
        participants.put(userId, vp);
        return new ArrayList<>(participants.values());
    }

    public synchronized List<VoiceParticipant> leaveRoom(int channelId, int userId) {
        return leaveRoom(channelId, userId, null);
    }

    public synchronized List<VoiceParticipant> leaveRoom(int channelId, int userId, String sessionId) {
        Map<Integer, VoiceParticipant> participants = rooms.get(channelId);
        if (participants == null) {
            return Collections.emptyList();
        }
        VoiceParticipant participant = participants.get(userId);
        if (participant == null || (sessionId != null && !sessionId.equals(participant.getSessionId()))) {
            return new ArrayList<>(participants.values());
        }
        participants.remove(userId);
        if (participants.isEmpty()) {
            rooms.remove(channelId);
            return Collections.emptyList();
        }
        return new ArrayList<>(participants.values());
    }

    public synchronized Map<Integer, List<VoiceParticipant>> leaveAllRooms(int userId) {
        return leaveAllRooms(userId, null);
    }

    public synchronized Map<Integer, List<VoiceParticipant>> leaveAllRooms(int userId, String sessionId) {
        Map<Integer, List<VoiceParticipant>> updatedChannels = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<Integer, VoiceParticipant>> entry : new ArrayList<>(rooms.entrySet())) {
            int channelId = entry.getKey();
            Map<Integer, VoiceParticipant> participants = entry.getValue();
            VoiceParticipant participant = participants.get(userId);
            if (participant != null && (sessionId == null || sessionId.equals(participant.getSessionId()))) {
                participants.remove(userId);
                if (participants.isEmpty()) {
                    rooms.remove(channelId);
                    updatedChannels.put(channelId, Collections.emptyList());
                } else {
                    updatedChannels.put(channelId, new ArrayList<>(participants.values()));
                }
            }
        }
        return updatedChannels;
    }

    public synchronized List<VoiceParticipant> updateState(int channelId, int userId, boolean muted, boolean cameraOn) {
        return updateState(channelId, userId, null, muted, cameraOn);
    }

    public synchronized List<VoiceParticipant> updateState(int channelId, int userId, String sessionId,
                                                            boolean muted, boolean cameraOn) {
        Map<Integer, VoiceParticipant> participants = rooms.get(channelId);
        if (participants == null) {
            return Collections.emptyList();
        }
        VoiceParticipant vp = participants.get(userId);
        if (vp != null && (sessionId == null || sessionId.equals(vp.getSessionId()))) {
            vp.setMuted(muted);
            vp.setCameraOn(cameraOn);
        }
        return new ArrayList<>(participants.values());
    }

    public synchronized List<VoiceParticipant> getParticipants(int channelId) {
        Map<Integer, VoiceParticipant> participants = rooms.get(channelId);
        if (participants == null || participants.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(participants.values());
    }
}
