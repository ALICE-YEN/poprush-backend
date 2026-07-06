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
    private final RedisStockService redisStockService; // 高併發扣庫存改由 Redis atomic operation 處理

    // constructor injection，Spring 會自動把 OrderRepository 和 ProductRepository 傳進來，所以不用自己 new OrderRepository()
    public OrderService(
            OrderRepository orderRepository,
            CampaignRepository campaignRepository,
            UserRepository userRepository,
            RedisStockService redisStockService
    ){
        this.orderRepository = orderRepository;
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
        this.redisStockService = redisStockService;
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
        Optional<Order> existingOrder = orderRepository.findByIdempotencyKeyAndUser_IdAndCampaign_Id(
                idempotencyKey, request.getUserId(), campaignId
        );

        // 發現同一個 user、同一場 campaign 底下，這個 Idempotency-Key 已經建立過訂單了，所以不再建立新訂單，直接回傳原本那筆 Order
        // 這一步一定要放在扣 Redis 庫存「之前」，不然同一個 idempotency key 重試會被重複扣庫存
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

        // 併發控制交給 Redis 了，這裡查 Campaign 只是為了拿時間區間跟關聯資訊，不需要悲觀鎖
        Campaign campaign = campaignRepository.findById(campaignId)
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

//        if(campaign.getStock() < request.getQuantity()){
//            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enough stock");
//        }
//
//        // 扣庫存
//        campaign.setStock(
//                campaign.getStock() - request.getQuantity()
//        );
//
//        campaignRepository.save(campaign); // 把扣完庫存的存回 DB


        // atomic 扣庫存：Redis 用一支 Lua script 完成「檢查庫存夠不夠 + 扣減」，不需要 DB 鎖
        long remainingStock = redisStockService.decreaseStock(campaignId, request.getQuantity());

        if(remainingStock == RedisStockService.NOT_INITIALIZED){
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stock not initialized in Redis");
        }

        if(remainingStock == RedisStockService.INSUFFICIENT_STOCK){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not enough stock");
        }

        // Redis 扣庫存成功之後，如果接下來任何一步失敗，都要把 Redis 庫存補回去，
        // 因為 Redis 不在 @Transactional 的 rollback 範圍內，不會自動復原
        try {
            // 把 DB 的庫存也同步扣掉，紀錄用，真正決定「搶不搶得到」的判斷在上面 Redis 那步就做完了
            campaignRepository.decreaseStock(campaignId, request.getQuantity());

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
                // 2. unique(idempotency_key, user_id, campaign_id)
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Duplicate order or duplicate idempotency key"
                ); // ResponseStatusException 是 RuntimeException
            }
        } catch (RuntimeException exception) {
            redisStockService.restoreStock(campaignId, request.getQuantity());
            throw exception;
        }
    }
}
