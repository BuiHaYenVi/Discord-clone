package com.discordmini.server.dao;

import com.discordmini.server.model.Message;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.protocol.ProtocolHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DataStore {
    private final Path dataDir;
    private final Path usersFile;
    private final Path roomsFile;
    private final Path messagesFile;

    private final Map<String, User> usersByUsername = new LinkedHashMap<>();
    private final Map<Integer, Room> roomsById = new LinkedHashMap<>();
    private final Map<Integer, List<Message>> messagesByRoom = new HashMap<>();

    private final AtomicInteger userSeq = new AtomicInteger(1);
    private final AtomicInteger roomSeq = new AtomicInteger(1);
    private final AtomicInteger messageSeq = new AtomicInteger(1);

    public DataStore() throws IOException {
        this.dataDir = Paths.get("data");
        this.usersFile = dataDir.resolve("users.txt");
        this.roomsFile = dataDir.resolve("rooms.txt");
        this.messagesFile = dataDir.resolve("messages.txt");
        Files.createDirectories(dataDir);
        load();
    }

    public synchronized User findUser(String username) {
        return usersByUsername.get(username.toLowerCase(Locale.ROOT));
    }

    public synchronized User register(String username, String password, String displayName) throws IOException {
        String key = username.toLowerCase(Locale.ROOT);
        if (usersByUsername.containsKey(key)) return null;
        User user = new User(userSeq.getAndIncrement(), username, password, displayName);
        usersByUsername.put(key, user);
        saveUsers();
        return user;
    }

    public synchronized List<Room> listRooms() {
        return new ArrayList<>(roomsById.values());
    }

    public synchronized Room findRoom(int id) { return roomsById.get(id); }

    public synchronized Room createRoom(String name) throws IOException {
        for (Room r : roomsById.values()) {
            if (r.getName().equalsIgnoreCase(name)) return null;
        }
        Room room = createRoomInternal(name);
        saveRooms();
        return room;
    }

    private Room createRoomInternal(String name) {
        Room room = new Room(roomSeq.getAndIncrement(), name);
        roomsById.put(room.getId(), room);
        return room;
    }

    public synchronized void addMessage(Message message) throws IOException {
        messagesByRoom.computeIfAbsent(message.getRoomId(), k -> new ArrayList<>()).add(message);
        try (var w = Files.newBufferedWriter(messagesFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(String.join("|",
                    Integer.toString(message.getId()),
                    Integer.toString(message.getRoomId()),
                    Integer.toString(message.getUserId()),
                    ProtocolHandler.encode(message.getSender()),
                    ProtocolHandler.encode(message.getContent()),
                    ProtocolHandler.encode(message.getTimestamp())));
            w.newLine();
        }
    }

    public synchronized int nextMessageId() { return messageSeq.getAndIncrement(); }

    public synchronized List<Message> history(int roomId) {
        return new ArrayList<>(messagesByRoom.getOrDefault(roomId, List.of()));
    }

    public synchronized Collection<User> allUsers() { return new ArrayList<>(usersByUsername.values()); }

    private void load() throws IOException {
        if (Files.exists(usersFile)) {
            for (String line : Files.readAllLines(usersFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] p = line.split("\\|", -1);
                if (p.length < 4) continue;
                try {
                    int id = Integer.parseInt(p[0]);
                    User u = new User(id, ProtocolHandler.decode(p[1]), ProtocolHandler.decode(p[2]), ProtocolHandler.decode(p[3]));
                    usersByUsername.put(u.getUsername().toLowerCase(Locale.ROOT), u);
                    userSeq.set(Math.max(userSeq.get(), id + 1));
                } catch (Exception ignored) {}
            }
        }
        if (Files.exists(roomsFile)) {
            for (String line : Files.readAllLines(roomsFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] p = line.split("\\|", -1);
                if (p.length < 2) continue;
                try {
                    int id = Integer.parseInt(p[0]);
                    Room r = new Room(id, ProtocolHandler.decode(p[1]));
                    roomsById.put(id, r);
                    roomSeq.set(Math.max(roomSeq.get(), id + 1));
                } catch (Exception ignored) {}
            }
        }
        if (Files.exists(messagesFile)) {
            for (String line : Files.readAllLines(messagesFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] p = line.split("\\|", -1);
                if (p.length < 6) continue;
                try {
                    Message m = new Message(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            ProtocolHandler.decode(p[3]), ProtocolHandler.decode(p[4]), ProtocolHandler.decode(p[5]));
                    messagesByRoom.computeIfAbsent(m.getRoomId(), k -> new ArrayList<>()).add(m);
                    messageSeq.set(Math.max(messageSeq.get(), m.getId() + 1));
                } catch (Exception ignored) {}
            }
        }
    }

    private void saveUsers() throws IOException {
        List<String> lines = new ArrayList<>();
        for (User u : usersByUsername.values()) {
            lines.add(String.join("|", Integer.toString(u.getId()), ProtocolHandler.encode(u.getUsername()),
                    ProtocolHandler.encode(u.getPassword()), ProtocolHandler.encode(u.getDisplayName())));
        }
        Files.write(usersFile, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void saveRooms() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Room r : roomsById.values()) {
            lines.add(String.join("|", Integer.toString(r.getId()), ProtocolHandler.encode(r.getName())));
        }
        Files.write(roomsFile, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
