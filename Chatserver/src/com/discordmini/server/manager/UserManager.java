package com.discordmini.server.manager;

import com.discordmini.server.model.User;
import java.util.Collection;

public class UserManager {
    public boolean verifyPassword(User user, String password) {
        return user != null && user.getPassword().equals(password);
    }
    public Collection<User> list(Collection<User> source) { return source; }
}
