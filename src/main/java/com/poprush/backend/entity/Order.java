package com.poprush.backend.entity;

import com.poprush.backend.entity.Order;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "orders", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_order_user_campaign",
                columnNames = {"user_id", "campaign_id"} // 同一個使用者不能重複搶同一場活動，user_id + campaign_id 不能重複
        ),
        @UniqueConstraint(
                name = "uk_order_idempotency_key",
                columnNames = {"idempotency_key"} // 同一個 Request 不能重複建立訂單，idempotency_key 不能重複
        )
}) // 避免踩到 SQL 保留字 order
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Integer quantity;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false) // foreign key
    private Campaign campaign; // 指定關聯的是 Product Entity，primary key 是 id

    //  Lombok 語法糖 @NoArgsConstructor，就不用寫以下模板
    //  public Order(){} // JPA 強制要求，Hibernate 需要無參數 constructor

    public Order(User user,Campaign campaign, Integer quantity,String idempotencyKey){
        this.user = user;
        this.campaign = campaign;
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
        this.status = OrderStatus.CONFIRMED;
        this.createdAt = LocalDateTime.now();
    }

    //  Lombok 語法糖 @Getter、@Setter，就不用寫以下模板
    //  public Long getId(){
    //      return id;
    //  }

    //  public Integer getQuantity(){
    //      return quantity;
    //  }

    //  public LocalDateTime getCreatedAt(){
    //      return createdAt;
    //  }
}
