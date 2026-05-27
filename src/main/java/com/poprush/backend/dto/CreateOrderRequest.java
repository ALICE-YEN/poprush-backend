package com.poprush.backend.dto;

public class CreateOrderRequest {
    private Long productId;

    private Integer quantity;

    public Long getProductId(){
        return productId;
    }

    public Integer getQuantity(){
        return quantity;
    }
}
