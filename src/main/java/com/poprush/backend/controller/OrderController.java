package com.poprush.backend.controller;

import com.poprush.backend.dto.CreateOrderRequest;
import com.poprush.backend.entity.Order;
import com.poprush.backend.repository.OrderRepository;
import com.poprush.backend.service.OrderService;
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
            @RequestBody CreateOrderRequest request
    ){
        return orderService.createOrder(request);
    }
}
