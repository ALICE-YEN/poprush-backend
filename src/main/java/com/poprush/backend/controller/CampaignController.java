package com.poprush.backend.controller;

import com.poprush.backend.dto.CreateOrderRequest;
import com.poprush.backend.repository.CampaignRepository;
import com.poprush.backend.service.OrderService;
import com.poprush.backend.entity.Campaign;
import com.poprush.backend.entity.Order;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
@RestController
@RequestMapping("/campaigns")
public class CampaignController {

    private final CampaignRepository campaignRepository;
    private final OrderService orderService;

    public CampaignController(
            CampaignRepository campaignRepository,
            OrderService orderService
    ){
        this.campaignRepository = campaignRepository;
        this.orderService = orderService;
    }

    @GetMapping
    public List<Campaign> getCampaigns(){
        return campaignRepository.findAll();
    }

    @GetMapping("/{id}")
    public Campaign getCampaign(
            @PathVariable Long id
    ){
        return campaignRepository.findById(id).orElseThrow(() -> new RuntimeException("Campaign not found"));
    }

    @PostMapping("/{campaignId}/orders")
    public Order createOrder(
            @PathVariable Long campaignId,
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Test-Fail-After-Stock-Deduct", defaultValue = "false") boolean failAfterStockDeduct // 測試模擬「扣完庫存後失敗」。從 HTTP header 讀一個叫做 X-Test-Fail-After-Stock-Deduct 的值
            ){
        return orderService.createOrder(campaignId, request, idempotencyKey, failAfterStockDeduct);
    }

}
