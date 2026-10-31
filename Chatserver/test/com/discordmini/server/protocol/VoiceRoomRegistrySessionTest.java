package com.discordmini.server.protocol;

import com.discordmini.server.model.User;
import com.discordmini.server.web.ws.VoiceRoomRegistry;

/** Regression coverage for keeping voice membership bound to its owning browser session. */
public final class VoiceRoomRegistrySessionTest {
    private static int assertions;

    public static void main(String[] args) {
        VoiceRoomRegistry registry = new VoiceRoomRegistry();
        User user = new User(41, "member", "", "Member", "ONLINE", null);
        registry.joinRoom(701, user, "tab-a", false, false);
        registry.joinRoom(701, user, "tab-b", false, false);

        check(registry.getParticipants(701).size() == 1, "one participant is shown per user");
        check("tab-b".equals(registry.getParticipants(701).get(0).getSessionId()),
                "the latest joined session owns the participant");
        check(registry.leaveAllRooms(user.getId(), "tab-a").isEmpty(),
                "closing the previous tab does not remove the current voice session");
        check(registry.getParticipants(701).size() == 1,
                "voice presence remains after an unrelated tab disconnects");
        check(registry.updateState(701, user.getId(), "tab-a", true, true)
                        .get(0).isMuted() == false,
                "an unrelated tab cannot change voice controls");
        check(registry.leaveAllRooms(user.getId(), "tab-b").containsKey(701),
                "closing the owning tab removes the participant");
        check(registry.getParticipants(701).isEmpty(), "voice presence clears when its owner leaves");

        User invisible = new User(42, "hidden", "", "Hidden", "OFFLINE", null);
        invisible.setPresenceStatus("OFFLINE");
        invisible.setStatus("ONLINE"); // transport connection state must not overwrite visibility preference
        check("OFFLINE".equals(invisible.getPresenceStatus()),
                "a new connection preserves the invisible setting");
        System.out.println("VoiceRoomRegistrySessionTest: " + assertions + " assertions passed");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
