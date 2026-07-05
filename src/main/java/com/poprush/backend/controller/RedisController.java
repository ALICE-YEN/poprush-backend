package com.poprush.backend.controller;

import org.springframework.data.redis.core.StringRedisTemplate; // 引入 StringRedisTemplate，Spring Data Redis 提供的工具，專門用來操作 Redis 裡的字串資料
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController // 告訴 Spring 這是一個 API Controller
@RequestMapping("/redis-test") // 設定這個 Controller 的共同路徑
public class RedisController {

    private final StringRedisTemplate redisTemplate; // final 代表這個欄位建立後不能再換成別的物件

    public RedisController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    } // Spring Boot 啟動時，會自動建立 StringRedisTemplate，然後傳進這個 Controller，不用自己寫 new StringRedisTemplate()

    /**
     * 測試寫入 Redis
     */
    @PostMapping("/set")
    public String setValue() { // 回傳型別是 String

        // 在 Redis 裡寫入資料
        redisTemplate.opsForValue().set(
                "test:message",
                "hello redis"
        );

        return "Redis SET Success";
    }

    /**
     * 測試讀取 Redis
     */
    @GetMapping("/get")
    public ResponseEntity<String> getValue() { // ResponseEntity 回傳完整 HTTP Response

        String value = redisTemplate.opsForValue().get("test:message");

        return value == null ? ResponseEntity.notFound().build() // HTTP/1.1 404 Not Found，Body 空
                : ResponseEntity.ok(value); // HTTP 200 OK，Body value
    }

}
