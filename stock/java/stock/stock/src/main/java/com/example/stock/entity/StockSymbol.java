package com.example.stock.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "stock_symbols")
public class StockSymbol {
    @Id
    private String symbol; // 例如 2330.TW

    public StockSymbol() {}

    public StockSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
}