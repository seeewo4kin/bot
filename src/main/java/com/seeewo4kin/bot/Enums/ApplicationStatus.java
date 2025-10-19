package com.seeewo4kin.bot.Enums;

public enum ApplicationStatus {
    FREE("🟢 Свободна"),
    IN_WORK("🟡 В работе"),
    CLOSED("🔵 Закрыта"),
    CANCELLED("🔴 Отменена");

    private final String displayName;

    ApplicationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}