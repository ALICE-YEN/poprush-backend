//用 Java class 表示資料庫裡的 product table
package com.poprush.backend.entity; // Jakarta Persistence API，JPA 規格

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*; // 引入 JPA 相關 annotation（@Entity、@Id、@GeneratedValue、GenerationType）
import lombok.Getter;
import lombok.Setter;

@Entity // 告訴 JPA 這個 class 要對應到資料庫的一張 table，Hibernate 實作
@Table(name = "products")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
@Getter // 自動幫這個 class 的所有欄位產生 getter
@Setter // 自動幫這個 class 的所有欄位產生 setter
public class Product { // 預設 Table 名稱 product。用 @Table(name = "product") 可能會是更好的習慣
    @Id // 表示下面這個欄位是 primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY) // id 由資料庫自動產生
    private Long id; // 商品唯一識別，由資料庫自動產生。Long 是因為資料庫 id 可能會越來越大

    private String name;

    private Integer price;

    // 商品總庫存，用於表示商品整體庫存概念
    // 實際搶購扣庫存會扣 Campaign.stock
    private Integer totalStock;

//    Lombok 語法糖，會自動產生以下的效果
//    public Integer getTotalStock() {
//        return totalStock;
//    }
//
//    public void setTotalStock(Integer totalStock) {
//        this.totalStock = totalStock;
//    }
}