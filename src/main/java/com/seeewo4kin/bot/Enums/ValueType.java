package com.seeewo4kin.bot.Enums;

public enum ValueType {
    RUB("🇷🇺 RUB"),
    BTC("₿ Bitcoin"),
    COUPONS("🎫 Купоны");

    private final String displayName;

    ValueType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}