// 管理 Redis 裡的 Idempotency 狀態，包含開始處理、查詢狀態、標記成功、標記失敗，以及 JSON 轉換
package com.poprush.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException; // 匯入 Jackson 在 JSON 轉換失敗時可能丟出的 exception
import com.fasterxml.jackson.databind.ObjectMapper; // 負責 Java Object ↔ JSON String
import com.poprush.backend.dto.CreateOrderResponse;
import com.poprush.backend.dto.RedisIdempotencyRecord;
import org.springframework.data.redis.core.StringRedisTemplate; // 匯入 Spring 操作 Redis 的工具
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class RedisIdempotencyService {

    private static final String KEY_PREFIX = "idempotency:campaign:";
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(30); // 當 request 正在處理時，Redis key 只保留 30 秒
    private static final Duration SUCCESS_TTL = Duration.ofHours(24); // 成功紀錄保存 24 小時
    private static final Duration FAILED_TTL = Duration.ofMinutes(5); // 失敗紀錄保存 5 分鐘

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String redisKey(Long campaignId, Long userId, String idempotencyKey) {
        return KEY_PREFIX + campaignId + ":user:" + userId + ":key:" + idempotencyKey;
    }

    // 嘗試搶占這個 Idempotency Key 的處理權
    public boolean startProcessing(Long campaignId, Long userId, String idempotencyKey) {
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                redisKey(campaignId, userId, idempotencyKey), // Redis key
                toJson(RedisIdempotencyRecord.processing()), // Redis value
                PROCESSING_TTL
        ); // SET NX 是 Redis（及 Valkey 等相容系統）中用來實現「只在鍵不存在時進行設定」的操作

        return Boolean.TRUE.equals(success); // 安全地把 Boolean（true 成功搶到處理權、false key 已存在、null 異常情況）轉成 boolean
    }

    // 查詢 Redis 紀錄
    public Optional<RedisIdempotencyRecord> getRecord(Long campaignId, Long userId, String idempotencyKey) {
        String value = redisTemplate.opsForValue().get(
                redisKey(campaignId, userId, idempotencyKey)
        );

        if (value == null) {
            return Optional.empty();
        }

        try {
            // 把 JSON 字串反序列化成 RedisIdempotencyRecord
            return Optional.of(
                    objectMapper.readValue(value, RedisIdempotencyRecord.class)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse idempotency record from Redis", exception);
        }
    }

    // 把 Redis 狀態從 PROCESSING 覆蓋成 SUCCESS，並保存第一次 response
    public void markSuccess(
            Long campaignId,
            Long userId,
            String idempotencyKey,
            CreateOrderResponse response
    ) {
        redisTemplate.opsForValue().set(
                redisKey(campaignId, userId, idempotencyKey),
                toJson(RedisIdempotencyRecord.success(response)),
                SUCCESS_TTL
        );
    }

    // 把 Redis 狀態改成 FAILED，並保存錯誤訊息
    public void markFailed(
            Long campaignId,
            Long userId,
            String idempotencyKey,
            String errorMessage
    ) {
        redisTemplate.opsForValue().set(
                redisKey(campaignId, userId, idempotencyKey),
                toJson(RedisIdempotencyRecord.failed(errorMessage)),
                FAILED_TTL
        );
    }

    // Java Object 轉 JSON
    private String toJson(RedisIdempotencyRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize idempotency record", exception);
        }
    }
}