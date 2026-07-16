// 原本 API 直接回傳 Order，會把 JPA Entity 暴露出去，改成 CreateOrderResponse 只回傳真正需要的資料
package com.poprush.backend.dto;

import com.poprush.backend.entity.Order;
import com.poprush.backend.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderResponse {

    private Long orderId;
    private Long userId;
    private Long campaignId;
    private Integer quantity;
    private OrderStatus status;
    private LocalDateTime createdAt;

    // static factory method，把 Order Entity 轉成 CreateOrderResponse DTO
    public static CreateOrderResponse from(Order order) {
        return new CreateOrderResponse(
                order.getId(),
                order.getUser().getId(),
                order.getCampaign().getId(),
                order.getQuantity(),
                order.getStatus(),
                order.getCreatedAt()
        );
    }
}