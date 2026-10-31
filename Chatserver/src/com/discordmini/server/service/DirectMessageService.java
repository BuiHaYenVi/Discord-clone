package com.discordmini.server.service;

import com.discordmini.server.dao.DirectMessageDAO;
import com.discordmini.server.dao.UserDAO;
import com.discordmini.server.model.DirectConversation;
import com.discordmini.server.model.DirectMessage;
import com.discordmini.server.protocol.ProtocolHandler;
import com.discordmini.server.session.EventBus;

import java.time.LocalTime;
import java.util.List;

public class DirectMessageService {
    private final DirectMessageDAO dmDAO;
    private final UserDAO userDAO;
    private final EventBus eventBus;

    public DirectMessageService() {
        this(new DirectMessageDAO(), new UserDAO(), EventBus.getInstance());
    }

    public DirectMessageService(DirectMessageDAO dmDAO, UserDAO userDAO, EventBus eventBus) {
        this.dmDAO = dmDAO != null ? dmDAO : new DirectMessageDAO();
        this.userDAO = userDAO != null ? userDAO : new UserDAO();
        this.eventBus = eventBus != null ? eventBus : EventBus.getInstance();
    }

    public List<DirectConversation> listConversations(int userId) {
        return dmDAO.listConversations(userId);
    }

    public DirectConversation getOrCreateConversation(int userA, int userB) {
        return dmDAO.getOrCreateConversation(userA, userB);
    }

    public List<DirectMessage> getMessages(long conversationId, Long beforeId, int limit) {
        return dmDAO.getMessages(conversationId, beforeId, limit);
    }

    public DirectMessage sendDirectMessage(int senderId, int targetUserId, String content,
                                           Long replyToId, List<Long> attachmentIds) {
        DirectConversation conv = dmDAO.getOrCreateConversation(senderId, targetUserId);
        if (conv == null) return null;
        return sendDirectMessage(conv.getId(), senderId, targetUserId, content, replyToId, attachmentIds);
    }

    public DirectMessage sendDirectMessage(long conversationId, int senderId, Integer peerUserId,
                                           String content, Long replyToId, List<Long> attachmentIds) {
        DirectMessage dm = dmDAO.saveDirectMessage(conversationId, senderId, content, replyToId, attachmentIds);
        if (dm == null) return null;

        eventBus.publishToUser(senderId, null, "DM_MESSAGE_CREATED", dm);

        if (peerUserId != null && peerUserId > 0 && peerUserId != senderId) {
            String timeStr = LocalTime.now().toString();
            if (timeStr.length() > 8) {
                timeStr = timeStr.substring(0, 8);
            }
            String tcpPacket = "NEW_PRIVATE_MESSAGE|"
                    + senderId + "|"
                    + ProtocolHandler.encode(dm.getSenderDisplayName()) + "|"
                    + ProtocolHandler.encode(dm.getContent()) + "|"
                    + ProtocolHandler.encode(timeStr);
            eventBus.publishToUser(peerUserId, tcpPacket, "DM_MESSAGE_CREATED", dm);
        }
        return dm;
    }

    public DirectMessageDAO getDirectMessageDAO() {
        return dmDAO;
    }
}
