package com.example.stock.repository;

import com.example.stock.entity.StockAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface StockAnalysisRepository extends JpaRepository<StockAnalysis, Long> {
    
    // 找出特定日期、分數及格、觸發次數足夠的股票，並依勝率降冪排序取前 10 名
    List<StockAnalysis> findTop10ByTradeDateAndScoreGreaterThanEqualAndTotalSignalsGreaterThanEqualOrderByWinRateDesc(
            LocalDate tradeDate, Integer minScore, Integer minSignals);
}