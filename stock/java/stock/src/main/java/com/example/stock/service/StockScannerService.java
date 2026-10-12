package com.example.stock.service;

import com.example.stock.entity.StockAnalysis;
import com.example.stock.repository.StockAnalysisRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class StockScannerService {

    @Autowired
    private StockAnalysisRepository repository;

    // 測試用股票池 (權值股與熱門股)
    private final List<String> STOCK_POOL = Arrays.asList("2330.TW", "2317.TW", "2454.TW", "2382.TW", "2308.TW", "2603.TW", "2881.TW");
    private final double TRANSACTION_COST = 0.006; // 交易摩擦成本約 0.6%

    static class Quote {
        LocalDate date; double close; long volume;
        double ma20, ma60, rsi; int score;
        public Quote(LocalDate date, double close, long volume) {
            this.date = date; this.close = close; this.volume = volume;
        }
    }

    // 每天下午 3 點自動執行 (Cron 語法)
    @Scheduled(cron = "0 0 15 * * ?")
    public void runDailyMarketScan() {
        System.out.println("🚀 啟動實戰級全市場掃描：" + LocalDate.now());

        try {
            System.out.println("正在獲取大盤趨勢...");
            List<Quote> twiiData = fetchYahooData("^TWII");
            Map<LocalDate, Boolean> marketTrend = buildMarketFilter(twiiData);

            for (String symbol : STOCK_POOL) {
                Thread.sleep(1500); // 避免被 Yahoo 封鎖 IP
                System.out.println("正在分析: " + symbol);
                List<Quote> data = fetchYahooData(symbol);
                if (data.size() < 100) continue;

                calculateIndicators(data);

                // 評分邏輯 (套用大盤濾網)
                for (Quote q : data) {
                    q.score = 0;
                    boolean isMarketGood = marketTrend.getOrDefault(q.date, false);
                    if (isMarketGood) { // 大盤站上季線才給分
                        if (q.close > q.ma20 && q.ma20 > q.ma60) q.score += 40;
                        if (q.rsi >= 50 && q.rsi <= 75) q.score += 30;
                        if (q.volume > 1000) q.score += 10;
                    }
                }

                analyzeAndSave(symbol, data);
            }
            System.out.println("✅ 掃描作業完成！");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void analyzeAndSave(String symbol, List<Quote> data) {
        int threshold = 70;
        int holdingDays = 20;
        LocalDate oneYearAgo = LocalDate.now().minusYears(1);

        int totalSignals = 0, winCount = 0;
        int oosSignals = 0, oosWinCount = 0;
        double totalNetReturn = 0;

        for (int i = 0; i < data.size(); i++) {
            Quote q = data.get(i);
            if (q.score >= threshold) {
                if (i + holdingDays < data.size()) {
                    double buyPrice = q.close;
                    double sellPrice = data.get(i + holdingDays).close;
                    
                    // 扣除交易手續費與稅金
                    double netReturn = ((sellPrice - buyPrice) / buyPrice) - TRANSACTION_COST; 

                    totalSignals++;
                    totalNetReturn += netReturn;
                    if (netReturn > 0) winCount++;

                    // OOS: 樣本外測試 (近一年)
                    if (q.date.isAfter(oneYearAgo)) {
                        oosSignals++;
                        if (netReturn > 0) oosWinCount++;
                    }
                }
            }
        }

        if (totalSignals > 0) {
            double winRate = ((double) winCount / totalSignals) * 100;
            double oosWinRate = oosSignals > 0 ? ((double) oosWinCount / oosSignals) * 100 : 0.0;
            double avgNetReturn = (totalNetReturn / totalSignals) * 100;

            StockAnalysis analysis = new StockAnalysis();
            analysis.setSymbol(symbol);
            analysis.setTradeDate(LocalDate.now());
            analysis.setScore(data.get(data.size() - 1).score);
            analysis.setWinRate(Math.round(winRate * 100.0) / 100.0);
            analysis.setOosWinRate(Math.round(oosWinRate * 100.0) / 100.0);
            analysis.setAvgReturn(Math.round(avgNetReturn * 100.0) / 100.0);
            analysis.setTotalSignals(totalSignals);
            
            repository.save(analysis);
        }
    }

    // HTTP 爬蟲獲取 Yahoo Finance 歷史資料
    private List<Quote> fetchYahooData(String symbol) throws Exception {
        long p1 = Instant.parse("2020-01-01T00:00:00Z").getEpochSecond();
        long p2 = Instant.now().getEpochSecond();
        String url = String.format("https://query1.finance.yahoo.com/v7/finance/download/%s?period1=%d&period2=%d&interval=1d&events=history", symbol, p1, p2);
        
        HttpResponse<String> res = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        List<Quote> quotes = new ArrayList<>();
        String[] lines = res.body().split("\n");
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",");
            if (cols.length >= 7 && !cols[4].equals("null")) {
                quotes.add(new Quote(LocalDate.parse(cols[0]), Double.parseDouble(cols[4]), (long)Double.parseDouble(cols[6])));
            }
        }
        return quotes;
    }

    // 建立大盤季線濾網
    private Map<LocalDate, Boolean> buildMarketFilter(List<Quote> twiiData) {
        Map<LocalDate, Boolean> trend = new HashMap<>();
        for (int i = 0; i < twiiData.size(); i++) {
            if (i >= 59) {
                double sum = 0;
                for (int j = 0; j < 60; j++) sum += twiiData.get(i - j).close;
                double ma60 = sum / 60;
                trend.put(twiiData.get(i).date, twiiData.get(i).close > ma60);
            }
        }
        return trend;
    }

    // 計算均線與 RSI
    private void calculateIndicators(List<Quote> data) {
        for (int i = 0; i < data.size(); i++) {
            if (i >= 19) {
                double sum20 = 0; for(int j=0; j<20; j++) sum20 += data.get(i-j).close;
                data.get(i).ma20 = sum20 / 20;
            }
            if (i >= 59) {
                double sum60 = 0; for(int j=0; j<60; j++) sum60 += data.get(i-j).close;
                data.get(i).ma60 = sum60 / 60;
            }
            if (i >= 14) {
                double gain = 0, loss = 0;
                for (int j = i - 13; j <= i; j++) {
                    double change = data.get(j).close - data.get(j - 1).close;
                    if (change > 0) gain += change; else loss -= change;
                }
                double rs = (loss == 0) ? 100 : (gain / 14) / (loss / 14);
                data.get(i).rsi = 100 - (100 / (1 + rs));
            }
        }
    }
}