// 用 Redis atomic operation 處理高併發扣庫存，取代原本 DB 悲觀鎖
package com.poprush.backend.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class RedisStockService { // 管理 Redis 裡的活動庫存

    // decreaseStock 的特殊回傳值：key 不存在（沒初始化過）
    public static final long NOT_INITIALIZED = -1;
    // decreaseStock 的特殊回傳值：庫存不夠，沒有扣減
    public static final long INSUFFICIENT_STOCK = -2;

    private static final String KEY_PREFIX = "stock:campaign:";

    // Lua script 由 Redis 單執行緒執行，GET -> 比較 -> DECRBY 這三步在同一個 script 內完成，
    // 不會有兩個 request 同時讀到「扣減前」的庫存值
    private static final DefaultRedisScript<Long> DECREASE_SCRIPT = new DefaultRedisScript<>(
            "local stock = redis.call('GET', KEYS[1])\n" +
                    "if stock == false then\n" +
                    "  return -1\n" +
                    "end\n" +
                    "stock = tonumber(stock)\n" +
                    "local qty = tonumber(ARGV[1])\n" +
                    "if stock < qty then\n" +
                    "  return -2\n" +
                    "end\n" +
                    "return redis.call('DECRBY', KEYS[1], qty)",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public RedisStockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private String stockKey(Long campaignId) {
        return KEY_PREFIX + campaignId;
    }

    // 把 DB 的庫存數字灌進 Redis，之後這個 key 就是搶購期間唯一的庫存裁判
    public void initStock(Long campaignId, int stock) {
        redisTemplate.opsForValue().set(stockKey(campaignId), String.valueOf(stock)); // Redis 的 value 是字串
    }

    // atomic「檢查庫存夠不夠並扣減」；回傳扣後剩餘庫存，或 NOT_INITIALIZED / INSUFFICIENT_STOCK
    public long decreaseStock(Long campaignId, int quantity) {
        Long result = redisTemplate.execute(
                DECREASE_SCRIPT,
                Collections.singletonList(stockKey(campaignId)),
                String.valueOf(quantity)
        );
        return result;
    }

    // 補償用：DB 後續步驟失敗時，把 Redis 已經扣掉的庫存加回去
    // 補償不是 transaction，Redis 扣庫存和 DB 建立訂單不是同一個 transaction，雖然解決高併發扣庫存，但還沒完整解決 Redis / DB 一致性問題
    public void restoreStock(Long campaignId, int quantity) {
        redisTemplate.opsForValue().increment(stockKey(campaignId), quantity);
    }
}
