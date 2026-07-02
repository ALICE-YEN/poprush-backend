package com.poprush.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaigns")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"}) // 因為用了 LAZY（延遲載入），Hibernate 回傳的不是實際物件，而是一個 Proxy（代理物件），會多一些 Hibernate 自己用的欄位 hibernateLazyInitializer
@Getter
@Setter
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) // 多個 Campaign 可以對應同一個 Product，需要時才查詢 Product
    @JoinColumn(name = "product_id", nullable = false) // 外鍵欄位，campaigns.product_id 關聯 products.id
    private Product product;

    private Integer stock; // 本場活動可搶庫存，高併發扣減目標

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
