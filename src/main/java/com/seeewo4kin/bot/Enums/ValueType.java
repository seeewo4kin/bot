package com.seeewo4kin.bot.Enums;

public enum ValueType {
    RUB("🇷🇺 RUB"),
    USD("🇺🇸 USD"),
    EUR("🇪🇺 EUR"),
    BTC("₿ Bitcoin"),
    ETH("Ξ Ethereum"),
    USDT("💵 USDT"),
    USDC("USDC");

    private final String displayName;

    ValueType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}