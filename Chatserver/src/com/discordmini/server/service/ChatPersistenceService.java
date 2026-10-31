package com.discordmini.server.service;

import com.discordmini.server.dao.MessageDAO;
import com.discordmini.server.model.Message;

import java.util.List;

/**
 * [HAI-06 & HAI-08] ChatPersistenceService: Điều phối lưu trữ dữ liệu trước khi Broadcast.
 * Đảm bảo nguyên tắc: Tin nhắn PHẢI được ghi nhận vào PostgreSQL thành công
 * trước khi Server cho phép phát tán (broadcast) tới các Client khác.
 */
public class ChatPersistenceService {

    private final MessageDAO messageDAO;

    public ChatPersistenceService() {
        this.messageDAO = new MessageDAO();
    }

    public ChatPersistenceService(MessageDAO messageDAO) {
        this.messageDAO = messageDAO != null ? messageDAO : new MessageDAO();
    }

    /**
     * [HAI-06] Kiểm tra và lưu tin nhắn vào CSDL trước khi Broadcast.
     * 
     * @param roomId ID phòng chat
     * @param senderId ID người gửi
     * @param content Nội dung tin nhắn
     * @return Message Đối tượng Message hoàn chỉnh nếu lưu DB thành công, null nếu thất bại
     * @throws Exception Ngoại lệ kèm thông báo chi tiết khi lưu CSDL thất bại
     */
    public Message persistBeforeBroadcast(int roomId, int senderId, String senderDisplayName, String content) throws Exception {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Nội dung tin nhắn không được để trống.");
        }

        System.out.println("[PERSISTENCE] Đang ghi tin nhắn vào PostgreSQL (Room=" + roomId + ", Sender=" + senderId + ")...");
        
        Message savedMessage = messageDAO.save(roomId, senderId, senderDisplayName, content);

        if (savedMessage == null || savedMessage.getId() <= 0) {
            System.err.println("[PERSISTENCE FAIL] Không thể ghi tin nhắn vào CSDL. Hủy thao tác Broadcast!");
            throw new Exception("Gửi tin nhắn thất bại, lỗi lưu trữ CSDL. Vui lòng thử lại!");
        }

        System.out.println("[PERSISTENCE SUCCESS] Ghi CSDL thành công (MsgID=" + savedMessage.getId() + "). Cho phép Broadcast.");
        return savedMessage;
    }

    public Message persistBeforeBroadcast(int roomId, int senderId, String content) throws Exception {
        return persistBeforeBroadcast(roomId, senderId, null, content);
    }

    /**
     * Phương thức kiêm tra đơn giản dạng boolean.
     */
    public boolean saveAndVerify(int roomId, int senderId, String content) {
        try {
            Message msg = persistBeforeBroadcast(roomId, senderId, content);
            return msg != null;
        } catch (Exception e) {
            System.err.println("[PERSISTENCE ERROR] " + e.getMessage());
            return false;
        }
    }

    /**
     * Truy vấn lịch sử tin nhắn phục vụ Client mới vào phòng (GET_HISTORY).
     */
    public List<Message> fetchHistory(int roomId, int limit) {
        return messageDAO.getMessageHistory(roomId, limit);
    }
}
