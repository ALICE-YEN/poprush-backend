// 「業務邏輯」通常放在 Service
package com.poprush.backend.service;

import com.poprush.backend.dto.CreateOrderRequest;
import com.poprush.backend.entity.Order;
import com.poprush.backend.entity.Product;
import com.poprush.backend.repository.OrderRepository;
import com.poprush.backend.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service // 告訴 Spring 這是一個 service 類別
public class OrderService {

    private final OrderRepository orderRepository; // 工具，用 OrderRepository 存訂單

    private final ProductRepository productRepository; // 工具，用 ProductRepository 查商品 / 存商品

    // constructor injection，Spring 會自動把 OrderRepository 和 ProductRepository 傳進來，所以不用自己 new OrderRepository()
    public OrderService(
            OrderRepository orderRepository,
            ProductRepository productRepository
    ){
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    public List<Order> getOrders(){
        return orderRepository.findAll();
    }

    public Order getOrder(Long id){
        return orderRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
    }

    // 這個方法裡面的所有 DB 操作要放在同一個 Transaction
    @Transactional
    public Order createOrder(CreateOrderRequest request, boolean failAfterStockDeduct){
        Product product = productRepository.findByIdForUpdate(request.getProductId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        if(product.getStock() < request.getQuantity()){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enough stock");
        }

        // 扣庫存
        product.setStock(
                product.getStock() - request.getQuantity()
        );

        productRepository.save(product); // 把扣完庫存的 product 存回 DB

        // 測試模擬「扣完庫存後失敗」
        if(failAfterStockDeduct){
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Test failure after stock deduct");
        }

        // 建立一筆新的訂單物件
        Order order = new Order(
                product,
                request.getQuantity()
        );

        return orderRepository.save(order); // 把訂單存進 DB，並回傳存好的結果
    }
}
