package com.seeewo4kin.bot.Controller;

import com.seeewo4kin.bot.ValueGettr.CryptoPriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StatusController {

    private final CryptoPriceService cryptoPriceService;

    public StatusController(CryptoPriceService cryptoPriceService) {
        this.cryptoPriceService = cryptoPriceService;
    }

    @GetMapping("/api/status/prices")
    public Map<String, Object> getPriceStatus() {
        return cryptoPriceService.getCacheStatus();
    }

    @PostMapping("/api/status/refresh-prices")
    public String refreshPrices() {
        cryptoPriceService.forcePriceUpdate();
        return "Prices refresh initiated";
    }
}