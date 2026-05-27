package com.poprush.backend.entity;

import com.poprush.backend.entity.Order;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "orders") // 避免踩到 SQL 保留字 order
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    private Long id;

    private Integer quantity;

    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "product_id") // foreign key
    private Product product; // 指定關聯的是 Product Entity，primary key 是 id

    public Order(){} // JPA 強制要求，Hibernate 需要無參數 constructor

    public Order(Product product, Integer quantity){
        this.product = product;
        this.quantity = quantity;
        this.createdAt = LocalDateTime.now();
    }

    // 可以考慮 Lombok 語法糖，就不用寫以下模板
    public Long getId(){
        return id;
    }

    public Integer getQuantity(){
        return quantity;
    }

    public LocalDateTime getCreatedAt(){
        return createdAt;
    }

    // 原本 product 是 private，外面不能直接拿 order.product
    // 改為 public，外面才可以 order.getProduct()
    public Product getProduct() {
        return product;
    }
}
