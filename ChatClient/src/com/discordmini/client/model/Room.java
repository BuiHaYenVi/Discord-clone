package com.discordmini.client.model;
public record Room(int id, String name) {
    @Override public String toString() { return name; }
}
