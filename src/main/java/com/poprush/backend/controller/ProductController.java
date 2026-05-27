// Controller 層，負責接 HTTP request
package com.poprush.backend.controller;

import com.poprush.backend.entity.Product;
import com.poprush.backend.repository.ProductRepository; // Controller 會透過此查詢商品資料
import org.springframework.web.bind.annotation.*; // 引入 Spring Web 的 annotation（@RestController、@RequestMapping、@GetMapping）

import java.util.List; // 引入 Java 的 List

@RestController // 告訴 Spring 這是一個 REST API Controller，回傳的資料會自動轉成 JSON
@RequestMapping("/products") // 設定這個 Controller 的共同路徑，這個 controller 底下的 API 都會從 /products 開始
public class ProductController {

    // 宣告
    private final ProductRepository productRepository; // private：只有這個 class 內可以用。final：建構後就不能換掉，代表這個 dependency 是固定的。

    // 初始化
    public ProductController(ProductRepository productRepository){ // constructor，Spring 會自動把 ProductRepository 注入進來
        this.productRepository = productRepository;
    }

    @GetMapping // 設定 HTTP GET API，因為 class 上面已經有 @RequestMapping("/products")，所以這個 method 對應的是：GET /products
    public List<Product> getProducts(){
        return productRepository.findAll();
    }


    @GetMapping("/{id}") // 設定 HTTP GET API，因為 class 上面已經有 @RequestMapping("/products")，所以這個 method 對應的是：GET /products/{id}
    public Product getProduct(@PathVariable Long id){
        return productRepository.findById(id).orElseThrow(() -> new RuntimeException("Product not found")); // Java 內建 java.lang.RuntimeException
    }
}