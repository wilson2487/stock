package com.example.stock.controller;

import com.example.stock.entity.StockAnalysis;
import com.example.stock.repository.StockAnalysisRepository;
import com.example.stock.service.StockScannerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    @Autowired
    private StockAnalysisRepository repository;
    
    @Autowired
    private StockScannerService scannerService;

    // 取得今日勝率 Top 10，設定門檻：70分以上、歷史訊號至少大於3次
    @GetMapping("/top10")
    public List<StockAnalysis> getTop10() {
        return repository.findTop10ByTradeDateAndScoreGreaterThanEqualAndTotalSignalsGreaterThanEqualOrderByWinRateDesc(
                LocalDate.now(), 70, 3
        );
    }

    // 手動觸發全市場掃描 (非同步)
    @PostMapping("/scan-now")
    public String triggerScanNow() {
        new Thread(() -> scannerService.runDailyMarketScan()).start();
        return "實戰級背景掃描已啟動，請查看 Console 進度。";
    }
}