package com.example.stock.repository;

import com.example.stock.entity.StockSymbol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockSymbolRepository extends JpaRepository<StockSymbol, String> {
}