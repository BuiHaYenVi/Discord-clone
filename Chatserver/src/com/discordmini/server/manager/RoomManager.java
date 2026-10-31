package com.discordmini.server.manager;

import com.discordmini.server.model.Room;
import com.discordmini.server.network.ClientHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RoomManager {
    private final Map<Integer, Set<ClientHandler>> roomClients = new ConcurrentHashMap<>();

    public Set<ClientHandler> clientsIn(int roomId) {
        return roomClients.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
    }

    public void join(Room room, ClientHandler client) { clientsIn(room.getId()).add(client); }
    public void leave(int roomId, ClientHandler client) {
        Set<ClientHandler> set = roomClients.get(roomId);
        if (set != null) set.remove(client);
    }
    public void leaveAll(ClientHandler client) {
        for (Set<ClientHandler> set : roomClients.values()) set.remove(client);
    }
}
