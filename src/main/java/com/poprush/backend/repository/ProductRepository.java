// repository 代表這裡放「資料庫操作相關的程式」
package com.poprush.backend.repository;

import com.poprush.backend.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository; // Spring Data JPA 提供的 JpaRepository，已經準備好很多常用 DB 操作（findAll、findById、save、deleteById...）

// 兩個泛型。Product：這個 Repository 操作的是 Product entity。Long：Product 的 primary key 型別是 Long
public interface ProductRepository extends JpaRepository<Product, Long>{
}