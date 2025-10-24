package com.seeewo4kin.bot.Enums;

public enum ApplicationStatus {
    FREE("🟢 Свободна"),
    IN_WORK("🟡 В работе"),
    COMPLETED("✅ Выполнена"), // Изменено с CLOSED на COMPLETED
    CANCELLED("🔴 Отменена");

    private final String displayName;

    ApplicationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}