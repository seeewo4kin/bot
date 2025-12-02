package com.seeewo4kin.bot.Controller;

import com.seeewo4kin.bot.ValueGettr.CryptoPriceService;
import com.seeewo4kin.bot.service.AdminStatisticsService;
import com.seeewo4kin.bot.service.XmrPriceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
public class StatusController {

    private final CryptoPriceService cryptoPriceService;
    private final XmrPriceService xmrPriceService;
    private final AdminStatisticsService adminStatisticsService;

    public StatusController(CryptoPriceService cryptoPriceService, XmrPriceService xmrPriceService, AdminStatisticsService adminStatisticsService) {
        this.cryptoPriceService = cryptoPriceService;
        this.xmrPriceService = xmrPriceService;
        this.adminStatisticsService = adminStatisticsService;
    }

    @GetMapping("/api/status/prices")
    public Map<String, Object> getPriceStatus() {
        return cryptoPriceService.getCacheStatus();
    }

    @GetMapping("/api/status/xmr-prices")
    public Map<String, Object> getXmrPriceStatus() {
        return xmrPriceService.getStatus();
    }

    @PostMapping("/api/status/refresh-prices")
    public String refreshPrices() {
        cryptoPriceService.forcePriceUpdate();
        return "Prices refresh initiated";
    }

    @PostMapping("/api/status/refresh-xmr-prices")
    public String refreshXmrPrices() {
        xmrPriceService.forcePriceUpdate();
        return "XMR prices refresh initiated";
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
            BigDecimal price;
            if ("XMR".equalsIgnoreCase(crypto)) {
                price = xmrPriceService.getXmrPrice(fiat);
            } else {
                price = cryptoPriceService.getFreshPrice(crypto, fiat);
            }
            return crypto + "/" + fiat + " = " + price;
        } catch (Exception e) {
            return "Error getting price: " + e.getMessage();
        }
    }

    @GetMapping("/api/status/test-statistics/{days}")
    public Map<String, Object> testStatistics(@PathVariable int days) {
        try {
            return adminStatisticsService.getGeneralStatistics(days);
        } catch (Exception e) {
            Map<String, Object> error = new java.util.HashMap<>();
            error.put("error", e.getMessage());
            error.put("stackTrace", e.getStackTrace());
            return error;
        }
    }

    @GetMapping("/api/status/test-statistics-formatted/{days}")
    public String testStatisticsFormatted(@PathVariable int days) {
        try {
            Map<String, Object> stats = adminStatisticsService.getGeneralStatistics(days);
            return adminStatisticsService.formatStatistics(stats, days + " дней");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}