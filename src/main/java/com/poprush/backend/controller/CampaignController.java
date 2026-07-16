package com.poprush.backend.controller;

import com.poprush.backend.dto.CreateOrderRequest;
import com.poprush.backend.dto.CreateOrderResponse;
import com.poprush.backend.repository.CampaignRepository;
import com.poprush.backend.service.OrderService;
import com.poprush.backend.entity.Campaign;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
        return campaignRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
    }

    @PostMapping("/{campaignId}/orders")
    public CreateOrderResponse createOrder( // 回 Redis 裡的 response DTO，不再回 JPA Entity（com.poprush.backend.entity.Order）
            @PathVariable Long campaignId,
            @Valid @RequestBody CreateOrderRequest request, // @Valid：讓 Spring 在進入 controller 方法前，依照 CreateOrderRequest 裡的 @NotNull、@Min(1) 這些規則驗證 request body。沒有 @Valid 的話，DTO 上那些 annotation 只是寫著好看，不會自動擋掉錯誤輸入
            @RequestHeader("Idempotency-Key") String idempotencyKey, // 請求的控制資訊（metadata），不是業務資料（business data），所以放 Header 比較符合 HTTP 的設計
            @RequestHeader(value = "X-Test-Fail-After-Stock-Deduct", defaultValue = "false") boolean failAfterStockDeduct // 測試模擬「扣完庫存後失敗」。從 HTTP header 讀一個叫做 X-Test-Fail-After-Stock-Deduct 的值
            ){
        return orderService.createOrder(campaignId, request, idempotencyKey, failAfterStockDeduct);
    }

}
