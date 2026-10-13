package com.example.stock.service;

import com.example.stock.entity.StockAnalysis;
import com.example.stock.entity.StockSymbol;
import com.example.stock.repository.StockAnalysisRepository;
import com.example.stock.repository.StockSymbolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class StockScannerService {

    @Autowired
    private StockAnalysisRepository analysisRepository;

    @Autowired
    private StockSymbolRepository symbolRepository;

    private final double TRANSACTION_COST = 0.006; // 交易摩擦成本 0.6%

    static class Quote {
        LocalDate date; double close; long volume;
        double ma20, ma60, rsi; int score;
        public Quote(LocalDate date, double close, long volume) {
            this.date = date; this.close = close; this.volume = volume;
        }
    }

    // 每天早上 8 點自動更新上市股票代號清單到資料庫
    @Scheduled(cron = "0 0 8 * * ?")
    public void updateStockSymbolsDaily() {
        System.out.println("🔄 開始同步更新台股代號清單...");
        try {
            String url = "https://openapi.twse.com.tw/v1/opendata/t187ap03_L";
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(response.body());
            
            List<StockSymbol> newSymbols = new ArrayList<>();
            for (JsonNode node : rootNode) {
                String code = node.path("公司代號").asText();
                if (code != null && code.matches("^\\d{4}$")) {
                    newSymbols.add(new StockSymbol(code + ".TW"));
                }
            }

            if (!newSymbols.isEmpty()) {
                // 可依需求決定是否先清空舊代號或直接 saveAll 覆蓋
                symbolRepository.saveAll(newSymbols);
                System.out.println("✅ 代號同步完成，共更新 " + newSymbols.size() + " 檔股票。");
            }
        } catch (Exception e) {
            System.out.println("❌ 同步台股代號失敗：" + e.getMessage());
        }
    }

    // 每天下午 3 點自動執行全市場掃描
    @Scheduled(cron = "0 0 15 * * ?")
    public void runDailyMarketScan() {
        System.out.println("🚀 啟動實戰級全市場掃描：" + LocalDate.now());

        try {
            System.out.println("正在獲取大盤趨勢...");
            List<Quote> twiiData = fetchYahooData("^TWII");
            Map<LocalDate, Boolean> marketTrend = buildMarketFilter(twiiData);

            // 從資料庫讀取股票代號清單
            List<StockSymbol> symbolEntities = symbolRepository.findAll();
            if (symbolEntities.isEmpty()) {
                System.out.println("⚠️ 資料庫中無股票代號，先執行一次同步...");
                updateStockSymbolsDaily();
                symbolEntities = symbolRepository.findAll();
            }

            System.out.println("總共載入 " + symbolEntities.size() + " 檔股票準備進行平行掃描...");

            // 使用執行緒池進行平行處理（設定 3 個執行緒避免被 Yahoo 封鎖 IP）
            ExecutorService executor = Executors.newFixedThreadPool(3);

            for (StockSymbol stockSymbol : symbolEntities) {
                String symbol = stockSymbol.getSymbol();
                executor.submit(() -> {
                    try {
                        Thread.sleep(1000); // 緩衝避免頻率過高
                        System.out.println("正在分析: " + symbol + " [執行緒: " + Thread.currentThread().getName() + "]");
                        
                        List<Quote> data = fetchYahooData(symbol);
                        if (data.size() < 100) return;

                        calculateIndicators(data);

                        // 評分邏輯 (套用大盤濾網)
                        for (Quote q : data) {
                            q.score = 0;
                            boolean isMarketGood = marketTrend.getOrDefault(q.date, false);
                            if (isMarketGood) { 
                                if (q.close > q.ma20 && q.ma20 > q.ma60) q.score += 40;
                                if (q.rsi >= 50 && q.rsi <= 75) q.score += 30;
                                if (q.volume > 1000) q.score += 10;
                            }
                        }

                        analyzeAndSave(symbol, data);
                    } catch (Exception e) {
                        System.out.println("分析代號 " + symbol + " 時發生錯誤: " + e.getMessage());
                    }
                });
            }

            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }

            System.out.println("✅ 全市場掃描作業完成！");
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
                    
                    double netReturn = ((sellPrice - buyPrice) / buyPrice) - TRANSACTION_COST; 

                    totalSignals++;
                    totalNetReturn += netReturn;
                    if (netReturn > 0) winCount++;

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
            
            analysisRepository.save(analysis);
        }
    }

    // HTTP 爬蟲獲取 Yahoo Finance 歷史資料（含 URL 編碼修復 ^TWII 錯誤）
    private List<Quote> fetchYahooData(String symbol) throws Exception {
        long p1 = Instant.parse("2020-01-01T00:00:00Z").getEpochSecond();
        long p2 = Instant.now().getEpochSecond();
        
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8.toString());
        String url = String.format("https://query1.finance.yahoo.com/v7/finance/download/%s?period1=%d&period2=%d&interval=1d&events=history", encodedSymbol, p1, p2);
        
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