package com.poprush.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RedisIdempotencyRecord {

    private String status;
    private CreateOrderResponse response;
    private String errorMessage;

    public static RedisIdempotencyRecord processing() {
        return new RedisIdempotencyRecord("PROCESSING", null, null);
    }

    public static RedisIdempotencyRecord success(CreateOrderResponse response) {
        return new RedisIdempotencyRecord("SUCCESS", response, null);
    }

    public static RedisIdempotencyRecord failed(String errorMessage) {
        return new RedisIdempotencyRecord("FAILED", null, errorMessage);
    }
}