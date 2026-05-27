package com.poprush.backend.controller;

import com.poprush.backend.entity.Order;
import com.poprush.backend.repository.OrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("orders")
public class OrderController {

    private final OrderRepository orderRepository;

    public OrderController(OrderRepository orderRepository){
        this.orderRepository = orderRepository;
    }

    @GetMapping
    public List<Order> getOrders(){
        return orderRepository.findAll();
    }


}
