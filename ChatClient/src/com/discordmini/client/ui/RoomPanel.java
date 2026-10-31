package com.discordmini.client.ui;

import com.discordmini.client.model.Room;
import com.discordmini.client.service.RoomEventListener;
import com.discordmini.client.service.RoomService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Panel quản lý danh sách phòng chat (cột bên trái).
 *
 * Tiêu chí AN-08:
 * - Hiển thị danh sách phòng theo đúng dữ liệu Server gửi về.
 * - Tích hợp RoomService trực tiếp, xử lý tạo phòng và rời phòng.
 * - Chuyển phòng đúng quy trình, cập nhật Swing an toàn trên EDT.
 */
public class RoomPanel extends JPanel implements RoomEventListener {
    private final RoomService roomService;
    private final DefaultListModel<Room> roomModel = new DefaultListModel<>();
    private final JList<Room> roomList = new JList<>(roomModel);
    private final JButton btnCreate = new JButton("➕ Tạo phòng");
    private final JButton btnRefresh = new JButton("🔄 Làm mới");
    private final JButton btnLeave = new JButton("🚪 Rời phòng");

    public RoomPanel(RoomService roomService) {
        this.roomService = roomService;
        setLayout(new BorderLayout(5, 5));
        setPreferredSize(new Dimension(220, 0));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        buildUi();
        if (roomService != null) {
            roomService.addListener(this);
            roomService.listRooms();
        }
    }

    private void buildUi() {
        JLabel title = new JLabel("  DANH SÁCH PHÒNG", SwingConstants.LEFT);
        title.setFont(new Font("Segoe UI", Font.BOLD, 13));
        title.setForeground(new Color(33, 33, 33));
        title.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        add(title, BorderLayout.NORTH);

        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomList.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        roomList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Room r) {
                    setText("# " + r.name());
                }
                setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                return this;
            }
        });
        add(new JScrollPane(roomList), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new GridLayout(3, 1, 4, 4));
        btnCreate.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnRefresh.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btnLeave.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btnPanel.add(btnCreate);
        btnPanel.add(btnRefresh);
        btnPanel.add(btnLeave);
        add(btnPanel, BorderLayout.SOUTH);

        btnRefresh.addActionListener(e -> {
            if (roomService != null) roomService.listRooms();
        });

        // Sự kiện chọn phòng từ danh sách (khi selection thay đổi)
        roomList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Room selected = roomList.getSelectedValue();
                if (selected != null && roomService != null) {
                    if (roomService.getClient() != null && !roomService.getClient().isConnected()) {
                        SwingUtilities.invokeLater(roomList::clearSelection);
                        return; // Đang mất kết nối thì không cố join phòng
                    }
                    Room current = roomService.getCurrentRoom();
                    if (current == null || current.id() != selected.id()) {
                        roomService.joinRoom(selected.id());
                    }
                }
            }
        });

        // Sự kiện click chuột trực tiếp (kể cả click vào phòng đang được highlight)
        roomList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = roomList.locationToIndex(e.getPoint());
                if (index >= 0 && roomService != null) {
                    Room selected = roomModel.getElementAt(index);
                    if (selected != null) {
                        if (roomService.getClient() != null && !roomService.getClient().isConnected()) {
                            SwingUtilities.invokeLater(roomList::clearSelection);
                            return;
                        }
                        Room current = roomService.getCurrentRoom();
                        if (current == null || current.id() != selected.id()) {
                            roomService.joinRoom(selected.id());
                        }
                    }
                }
            }
        });

        btnCreate.addActionListener(e -> onCreateRoom());
        btnLeave.addActionListener(e -> onLeaveRoom());
    }

    private void onCreateRoom() {
        String name = JOptionPane.showInputDialog(this, "Nhập tên phòng mới:", "Tạo phòng chat", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.isBlank() && roomService != null) {
            roomService.createRoom(name.trim());
        }
    }

    private void onLeaveRoom() {
        if (roomService != null) {
            roomService.leaveRoom();
        }
    }

    public JList<Room> getRoomList() {
        return roomList;
    }

    public DefaultListModel<Room> getRoomModel() {
        return roomModel;
    }

    // ================= RoomEventListener Callbacks =================

    @Override
    public void onRoomListUpdated(List<Room> rooms) {
        SwingUtilities.invokeLater(() -> {
            Room currentSelected = roomList.getSelectedValue();
            int selectedId = (currentSelected != null) ? currentSelected.id() : -1;

            roomModel.clear();
            int reselectIndex = -1;
            for (int i = 0; i < rooms.size(); i++) {
                Room r = rooms.get(i);
                roomModel.addElement(r);
                if (r.id() == selectedId) {
                    reselectIndex = i;
                }
            }

            if (reselectIndex != -1) {
                roomList.setSelectedIndex(reselectIndex);
            }
        });
    }

    @Override
    public void onRoomJoined(Room room) {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < roomModel.size(); i++) {
                if (roomModel.get(i).id() == room.id()) {
                    roomList.setSelectedIndex(i);
                    break;
                }
            }
        });
    }

    @Override
    public void onRoomLeft() {
        SwingUtilities.invokeLater(() -> {
            roomList.clearSelection();
        });
    }

    @Override
    public void onRoomCreated(Room room) {
        // Danh sách phòng sẽ được tự động refresh qua listRooms() trong RoomService
    }

    @Override
    public void onRoomError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            Room current = (roomService != null) ? roomService.getCurrentRoom() : null;
            if (current != null) {
                for (int i = 0; i < roomModel.size(); i++) {
                    if (roomModel.get(i).id() == current.id()) {
                        roomList.setSelectedIndex(i);
                        break;
                    }
                }
            } else {
                roomList.clearSelection();
            }
            JOptionPane.showMessageDialog(this, errorMessage, "Lỗi phòng", JOptionPane.WARNING_MESSAGE);
        });
    }
}
