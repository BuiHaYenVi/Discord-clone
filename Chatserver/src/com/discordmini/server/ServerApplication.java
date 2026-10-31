package com.discordmini.server;

import com.discordmini.server.network.ChatServer;

public class ServerApplication {
    public static void main(String[] args) {
        try {
            new ChatServer().start();
        } catch (Exception e) {
            System.err.println("Không thể khởi động server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
