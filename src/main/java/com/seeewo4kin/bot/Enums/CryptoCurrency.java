package com.seeewo4kin.bot.Enums;


public enum CryptoCurrency {
    BTC("Bitcoin", "BTC", "₿"),
    LTC("Litecoin", "LTC", "Ł"),
    XMR("Monero", "XMR", "ɱ"),
    RUB("Рубль", "RUB", "S");

    private final String displayName;
    private final String symbol;
    private final String icon;

    // конструктор и геттеры
    CryptoCurrency(String displayName, String symbol, String icon) {
        this.displayName = displayName;
        this.symbol = symbol;
        this.icon = icon;
    }

    public String getDisplayName() { return displayName; }
    public String getSymbol() { return symbol; }
    public String getIcon() { return icon; }
}