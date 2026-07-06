// 不管 dev/prod，app 啟動時都要確保 Redis 庫存跟 DB 對得上
// 每次 Spring Boot 啟動時，確保 Redis 至少擁有所有 Campaign 的初始庫存，但不會覆蓋已經在 Redis 中變動過的庫存。
package com.poprush.backend;

import com.poprush.backend.repository.CampaignRepository;
import com.poprush.backend.service.RedisStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component // Spring 啟動時會建立這個物件
@Order(2) // 在 DataInitializer（Order 1，只有 dev 會跑）塞完假資料之後執行，這樣新建的 campaign 也會被涵蓋到
@RequiredArgsConstructor // Lombok 會自動產生 constructor，把 final 的 repository 注入進來
public class RedisStockWarmupRunner implements CommandLineRunner {

    private final CampaignRepository campaignRepository;
    private final RedisStockService redisStockService;

    @Override
    public void run(String... args) {
        // initStockIfAbsent 用 SETNX，已經有 key 的 campaign 不會被動到，
        // 只有「全新 campaign」或「Redis 資料不見了」的 campaign 會被補上 DB 目前的庫存
        campaignRepository.findAll().forEach(
                c -> redisStockService.initStockIfAbsent(c.getId(), c.getStock())
        );
    }
}
