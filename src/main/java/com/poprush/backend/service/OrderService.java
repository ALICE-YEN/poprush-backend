// 「業務邏輯」通常放在 Service
package com.poprush.backend.service;

import com.poprush.backend.dto.CreateOrderRequest;
import com.poprush.backend.entity.Campaign;
import com.poprush.backend.entity.Order;
import com.poprush.backend.entity.User;
import com.poprush.backend.repository.CampaignRepository;
import com.poprush.backend.repository.OrderRepository;
import com.poprush.backend.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service // 告訴 Spring 這是一個 service 類別
public class OrderService {

    private final OrderRepository orderRepository; // 工具，用 OrderRepository 存訂單
    private final CampaignRepository campaignRepository;
    private final UserRepository userRepository;

    // constructor injection，Spring 會自動把 OrderRepository 和 ProductRepository 傳進來，所以不用自己 new OrderRepository()
    public OrderService(
            OrderRepository orderRepository,
            CampaignRepository campaignRepository,
            UserRepository userRepository
    ){
        this.orderRepository = orderRepository;
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
    }


    public List<Order> getOrders(){
        return orderRepository.findAll();
    }

    public Order getOrder(Long id){
        return orderRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
    }

    // 這個方法裡面的所有 DB 操作要放在同一個 Transaction
    @Transactional
    public Order createOrder(Long campaignId, CreateOrderRequest request, String idempotencyKey, boolean failAfterStockDeduct){
        Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);

        // 發現這個 Idempotency-Key 已經建立過訂單了，所以不再建立新訂單，直接回傳原本那筆 Order
        if(existingOrder.isPresent()){
            return existingOrder.get();
        }

        User user = userRepository.findById(
                request.getUserId()
        ).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                )
        );

        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Campaign not found"
                        )
                );

        LocalDateTime now = LocalDateTime.now();

        if(campaign.getStartTime() != null && now.isBefore(campaign.getStartTime())){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campaign has not started");
        }

        if(campaign.getEndTime() != null && now.isAfter(campaign.getEndTime())){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campaign has ended");
        }

        if(campaign.getStock() < request.getQuantity()){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enough stock");
        }

        // 扣庫存
        campaign.setStock(
                campaign.getStock() - request.getQuantity()
        );

        campaignRepository.save(campaign); // 把扣完庫存的存回 DB

        // 測試模擬「扣完庫存後失敗」
        if(failAfterStockDeduct){
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Test failure after stock deduct");
        }

        // 建立一筆新的訂單物件
        Order order = new Order(
                user,
                campaign,
                request.getQuantity(),
                idempotencyKey
        );

        try {
            return orderRepository.save(order); // 把訂單存進 DB，並回傳存好的結果
        } catch (DataIntegrityViolationException exception){
            // DB unique constraint 最後防線：
            // 1. unique(user_id, campaign_id)
            // 2. unique(idempotency_key)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Duplicate order or duplicate idempotency key"
            );
        }
    }
}
