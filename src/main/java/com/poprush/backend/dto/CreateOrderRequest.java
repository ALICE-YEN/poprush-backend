// 定義 request body 的型別
package com.poprush.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 建立搶購訂單的 request body
// campaignId 不放在 body，改從 URL /campaigns/{campaignId}/orders 取得
public class CreateOrderRequest {
    @NotNull
    private Long userId;

    @NotNull
    @Min(1)
    private Integer quantity;

    public Long getUserId(){
        return userId;
    }

    public Integer getQuantity(){
        return quantity;
    }
}
