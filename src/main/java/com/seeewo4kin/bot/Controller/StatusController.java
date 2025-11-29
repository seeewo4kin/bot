package com.seeewo4kin.bot.Controller;

import com.seeewo4kin.bot.ValueGettr.CryptoPriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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

    @PostMapping("/api/status/test-eur")
    public String testEur() {
        // Прямой тест обновления EUR
        try {
            // Установим фиксированное значение EUR для тестирования
            cryptoPriceService.getCacheStatus(); // Это вызовет инициализацию если нужно
            return "EUR test initiated";
        } catch (Exception e) {
            return "EUR test failed: " + e.getMessage();
        }
    }

    @PostMapping("/api/status/set-eur-rate")
    public String setEurRate() {
        // Прямое установление курса EUR для тестирования
        try {
            cryptoPriceService.forceEurUpdate();
            Map<String, Object> status = cryptoPriceService.getCacheStatus();
            return "EUR update attempted. Current status: " + status.toString();
        } catch (Exception e) {
            return "Set EUR rate failed: " + e.getMessage();
        }
    }

    @GetMapping("/api/status/test-price/{crypto}/{fiat}")
    public String testPrice(@PathVariable String crypto, @PathVariable String fiat) {
        try {
            BigDecimal price = cryptoPriceService.getFreshPrice(crypto, fiat);
            return crypto + "/" + fiat + " = " + price;
        } catch (Exception e) {
            return "Error getting price: " + e.getMessage();
        }
    }
}