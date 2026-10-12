package com.example.stock.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "stock_analysis")
public class StockAnalysis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String symbol;
    private LocalDate tradeDate;
    private Integer score;
    private Double winRate;
    private Double oosWinRate; // 近一年樣本外勝率
    private Double avgReturn;  // 扣除手續費後的淨報酬
    private Integer totalSignals;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    
    public Double getWinRate() { return winRate; }
    public void setWinRate(Double winRate) { this.winRate = winRate; }
    
    public Double getOosWinRate() { return oosWinRate; }
    public void setOosWinRate(Double oosWinRate) { this.oosWinRate = oosWinRate; }
    
    public Double getAvgReturn() { return avgReturn; }
    public void setAvgReturn(Double avgReturn) { this.avgReturn = avgReturn; }
    
    public Integer getTotalSignals() { return totalSignals; }
    public void setTotalSignals(Integer totalSignals) { this.totalSignals = totalSignals; }
}