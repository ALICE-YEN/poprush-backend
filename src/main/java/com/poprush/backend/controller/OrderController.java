package com.poprush.backend.controller;

import com.poprush.backend.dto.CreateOrderRequest;
import com.poprush.backend.entity.Order;
import com.poprush.backend.repository.OrderRepository;
import com.poprush.backend.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    // 宣告
    private final OrderService orderService ;

    // 初始化
    public OrderController(OrderService orderService, OrderRepository orderRepository){
        this.orderService = orderService;
    }

    @GetMapping
    public List<Order> getOrders(){
        return orderService.getOrders();
    }

    @GetMapping("/{id}")
    public Order getOrder(@PathVariable Long id){
        return orderService.getOrder(id);
    }

    @PostMapping
    public Order createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "X-Test-Fail-After-Stock-Deduct", defaultValue = "false") boolean failAfterStockDeduct // // 測試模擬「扣完庫存後失敗」。從 HTTP header 讀一個叫做 X-Test-Fail-After-Stock-Deduct 的值
    ){
        return orderService.createOrder(request, failAfterStockDeduct);
    }
}
