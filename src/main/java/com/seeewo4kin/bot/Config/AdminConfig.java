package com.seeewo4kin.bot.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

@Configuration
public class AdminConfig {

    private final Set<Long> adminUserIds = new HashSet<>();

    public AdminConfig(@Value("${bot.admins:}") String adminIds) {
        if (adminIds != null && !adminIds.trim().isEmpty()) {
            String[] ids = adminIds.split(",");
            for (String id : ids) {
                try {
                    adminUserIds.add(Long.parseLong(id.trim()));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid admin ID: " + id);
                }
            }
        }
    }

    public boolean isAdmin(Long userId) {
        return adminUserIds.contains(userId);
    }

    public Set<Long> getAdminUserIds() {
        return new HashSet<>(adminUserIds);
    }
}