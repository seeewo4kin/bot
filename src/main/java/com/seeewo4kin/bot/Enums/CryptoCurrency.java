package com.seeewo4kin.bot.Enums;

public enum CryptoCurrency {
    BTC("₿ Bitcoin", "₿", "₿"),
    LTC("Ł Litecoin", "Ł", "Ł"),
    XMR("ɱ Monero", "ɱ", "ɱ");

    private final String displayName;
    private final String symbol;
    private final String icon;

    CryptoCurrency(String displayName, String symbol, String icon) {
        this.displayName = displayName;
        this.symbol = symbol;
        this.icon = icon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getIcon() {
        return icon;
    }
}
