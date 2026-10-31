package com.discordmini.server.service;

import com.discordmini.server.dao.GuildDAO;
import com.discordmini.server.dao.RoomDAO;
import com.discordmini.server.model.Guild;
import com.discordmini.server.model.Room;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.session.EventBus;

import java.util.List;

public class GuildService {
    private final GuildDAO guildDAO;
    private final RoomDAO roomDAO;
    private final EventBus eventBus;

    public GuildService() {
        this(new GuildDAO(), new RoomDAO(), EventBus.getInstance());
    }

    public GuildService(GuildDAO guildDAO, RoomDAO roomDAO, EventBus eventBus) {
        this.guildDAO = guildDAO != null ? guildDAO : new GuildDAO();
        this.roomDAO = roomDAO != null ? roomDAO : new RoomDAO();
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
    }

    public List<Guild> listGuildsForUser(int userId) {
        return guildDAO.listGuildsForUser(userId);
    }

    public Guild findById(int guildId) {
        return guildDAO.findById(guildId);
    }

    public Guild createGuild(String name, int ownerId) {
        return createGuild(name, null, ownerId);
    }

    public Guild createGuild(String name, String iconText, int ownerId) {
        Guild created = guildDAO.createGuild(name, iconText, ownerId);
        if (created != null) {
            String inviteCode = guildDAO.createInvite(created.getId(), ownerId, 1440, 0);
            String safeInvite = inviteCode != null ? inviteCode : "";
            String tcpFrame = "GUILD_CREATED|"
                    + created.getId() + "|"
                    + ProtocolHandler.encode(created.getName()) + "|"
                    + ownerId + "|"
                    + safeInvite;
            eventBus.publishToUser(ownerId, tcpFrame, "GUILD_CREATED", created);
        }
        return created;
    }

    public Room createChannel(int guildId, String name, String channelType, int createdBy) {
        return createChannel(guildId, name, channelType, "", 0, createdBy);
    }

    public Room createChannel(int guildId, String name, String channelType, String topic, int position, int createdBy) {
        Room channel = roomDAO.createChannel(guildId, name, channelType, topic, position, createdBy);
        if (channel != null) {
            String tcpFrame = "CHANNEL_CREATED|"
                    + channel.getGuildId() + "|"
                    + channel.getId() + "|"
                    + ProtocolHandler.encode(channel.getName()) + "|"
                    + channel.getChannelType();
            eventBus.publishGlobal(tcpFrame, "CHANNEL_CREATED", channel, null);
        }
        return channel;
    }

    public List<Room> listChannelsByGuild(int guildId, int userId) {
        return roomDAO.listChannelsByGuild(guildId, userId);
    }

    public String createInvite(int guildId, int createdBy) {
        return guildDAO.createInvite(guildId, createdBy, 1440, 0);
    }

    public String createInvite(int guildId, int createdBy, int ttlMinutes, int maxUses) {
        return guildDAO.createInvite(guildId, createdBy, ttlMinutes, maxUses);
    }

    public Guild joinByInviteCode(String code, int userId) {
        Guild joined = guildDAO.joinByInviteCode(code, userId);
        if (joined != null) {
            eventBus.publishToUser(userId, null, "GUILD_JOINED", joined);
        }
        return joined;
    }

    public boolean addMember(int guildId, int userId, String role) {
        return guildDAO.addMember(guildId, userId, role);
    }

    public String getMemberRole(int guildId, int userId) {
        return guildDAO.getMemberRole(guildId, userId);
    }

    public boolean isAdminOrOwner(int guildId, int userId) {
        String role = getMemberRole(guildId, userId);
        return "OWNER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
    }

    public GuildDAO getGuildDAO() {
        return guildDAO;
    }
}
