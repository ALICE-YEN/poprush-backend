// 「業務邏輯」通常放在 Service
package com.poprush.backend.service;

import com.poprush.backend.dto.CreateOrderRequest;
import com.poprush.backend.dto.CreateOrderResponse;
import com.poprush.backend.dto.RedisIdempotencyRecord;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final RedisIdempotencyService redisIdempotencyService; // 使用 Redis 管理冪等請求狀態，避免相同請求被重複處理

    // constructor injection，Spring 會自動把 OrderRepository 和 ProductRepository 傳進來，所以不用自己 new OrderRepository()
    public OrderService(
            OrderRepository orderRepository,
            CampaignRepository campaignRepository,
            UserRepository userRepository,
            RedisStockService redisStockService,
            RedisIdempotencyService redisIdempotencyService
    ){
        this.orderRepository = orderRepository;
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
        this.redisStockService = redisStockService;
        this.redisIdempotencyService = redisIdempotencyService;
    }

    public List<Order> getOrders(){
        return orderRepository.findAll();
    }

    public Order getOrder(Long id){
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found"));
    }

    @Transactional // 這個方法裡的 DB 操作包在同一個 transaction，Redis 不在這個 transaction 裡
    public CreateOrderResponse createOrder(
            Long campaignId,
            CreateOrderRequest request,
            String idempotencyKey,
            boolean failAfterStockDeduct
    ){
        Long userId = request.getUserId();

        // 嘗試搶占 Idempotency Key，底層是：SET key PROCESSING NX EX ttl
        boolean isFirstRequest = redisIdempotencyService.startProcessing(
                campaignId,
                userId,
                idempotencyKey
        );

        // 不是第一個成功搶到 redis key 的 request，表示：這是 retry 或 同一個 key 的併發 request
        if (!isFirstRequest) {
            Optional<RedisIdempotencyRecord> record = redisIdempotencyService.getRecord(
                    campaignId,
                    userId,
                    idempotencyKey
            ); // 可能 PROCESSING、SUCCESS、FAILED，也可能剛好不存在，所以用 Optional

            if (record.isPresent()) {
                RedisIdempotencyRecord redisRecord = record.get();

                if ("SUCCESS".equals(redisRecord.getStatus())) {
                    return redisRecord.getResponse();
                }

                if ("PROCESSING".equals(redisRecord.getStatus())) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Same idempotency key is still processing"
                    );
                }

                if ("FAILED".equals(redisRecord.getStatus())) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            redisRecord.getErrorMessage()
                    );
                }
            }

            // isFirstRequest == false 且 record.isPresent() == false
            // SET key PROCESSING NX 告訴你：「key 已經存在，所以不能建立。」，緊接著 GET key 卻告訴你：「key 不存在。」，乍看矛盾，其實是因為 SET NX 與 GET 是兩個獨立的 Redis 操作，中間存在很短的時間差
            // 如果發生：key 在兩個 Redis 操作之間過期、key 被刪除、Redis 狀態異常
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency state changed during request; the record is no longer available"
            );
        }

        boolean redisStockDeducted = false; // 記錄 Redis 庫存是否扣過

        // DB Idempotency 最後防線，即使 Redis 沒有資料，仍然查 DB。
        // Redis idempotency record 可能因為以下原因消失：Redis TTL 過期、Redis 被清空、服務重啟後 Redis 資料遺失
        // 會多一次 DB SELECT，但 Redis 的主要效益仍然存在：擋住同一 idempotency key 的同時併發。PROCESSING 時避免重複執行後續流程。SUCCESS retry 可以直接從 Redis 回傳，不進入這段 DB 查詢。減少重複扣 Redis/DB 庫存
        // Redis 負責快速協調與快取結果，DB 負責最終真相
        // DB select，取得原本訂單才能修復 Redis SUCCESS
        try {
            Optional<Order> existingOrder = orderRepository.findByIdempotencyKeyAndUser_IdAndCampaign_Id(
                    idempotencyKey,
                    userId,
                    campaignId
            );

            // 如果 DB 已經有同一筆 idempotency 訂單
            if(existingOrder.isPresent()){
                CreateOrderResponse response = CreateOrderResponse.from(existingOrder.get()); // 把 DB 的 Order Entity 轉成 response DTO

                // 修復 Redis
                redisIdempotencyService.markSuccess(
                        campaignId,
                        userId,
                        idempotencyKey,
                        response
                );

                return response;
            }

            User user = userRepository.findById(userId)
                    .orElseThrow(() ->
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

            // atomic 扣庫存：Redis 用一支 Lua script 完成「檢查庫存夠不夠 + 扣減」，不需要 DB 鎖
            long remainingStock = redisStockService.decreaseStock(
                    campaignId,
                    request.getQuantity()
            );

            if(remainingStock == RedisStockService.NOT_INITIALIZED){
                // 可能是 campaign 是 app 啟動後才建立的，或 Redis 資料不見了，用手上已經查到的 DB 庫存自我修復一次再重試
                redisStockService.initStockIfAbsent(campaignId, campaign.getStock());

                remainingStock = redisStockService.decreaseStock(
                        campaignId,
                        request.getQuantity()
                );
            }

            if(remainingStock == RedisStockService.NOT_INITIALIZED){
                // 修復後還是失敗，代表 Redis 有更深層的問題，直接讓錯誤浮出來，不要無限重試
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Stock not initialized in Redis"
                );
            }

            if(remainingStock == RedisStockService.INSUFFICIENT_STOCK){
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Not enough stock"
                );
            }

            redisStockDeducted = true; // Redis 已經成功扣庫存

            // 同步扣 DB stock
            int updatedRows = campaignRepository.decreaseStock(
                    campaignId,
                    request.getQuantity()
            );

            // 沒有任何 row 被更新
            if(updatedRows == 0){
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "DB stock already exhausted"
                );
            }

            // 測試模擬「扣完庫存後失敗」
            if(failAfterStockDeduct){
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Test failure after stock deduct"
                );
            }

            // 建立一筆新的訂單物件
            Order order = new Order(
                    user,
                    campaign,
                    request.getQuantity(),
                    idempotencyKey
            );

            try {
                // saveAndFlush() 會先把 INSERT 真正送到 DB，
                // 讓 unique constraint 等 DB 錯誤能在這個 try-catch 內被發現。
                // 但此時 transaction 仍然還沒有 commit。
                Order savedOrder = orderRepository.saveAndFlush(order);

                CreateOrderResponse response = CreateOrderResponse.from(savedOrder); // 轉成 Response DTO

                // 不在這裡立刻把 Redis 改成 SUCCESS。
                // registerSynchronization() 會等 Spring 真正完成 transaction：
                // 1. commit 成功後，afterCommit() 才把 Redis 改成 SUCCESS。
                // 2. 如果 commit / rollback 失敗，afterCompletion() 會補回 Redis 庫存並標記 FAILED。
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                redisIdempotencyService.markSuccess(
                                        campaignId,
                                        userId,
                                        idempotencyKey,
                                        response
                                );
                            }

                            // afterCompletion() 的意思不是「成功後」，Transaction 已經結束（Completion），不管是 Commit 還是 Rollback，都會呼叫
                            // status 會告訴你：STATUS_COMMITTED、STATUS_ROLLED_BACK、STATUS_UNKNOWN
                            @Override
                            public void afterCompletion(int status) {
                                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                                    // 因為 Redis 不在 @Transactional 的 rollback 範圍內，不會自動復原
                                    redisStockService.restoreStock(
                                            campaignId,
                                            request.getQuantity()
                                    );

                                    redisIdempotencyService.markFailed(
                                            campaignId,
                                            userId,
                                            idempotencyKey,
                                            "Database transaction rolled back"
                                    );
                                }
                            }
                        }
                );

                return response;

            } catch (DataIntegrityViolationException exception){
                // DB unique constraint 最後防線：
                // 1. unique(user_id, campaign_id)
                // 2. unique(idempotency_key, user_id, campaign_id)
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Duplicate order or duplicate idempotency key"
                ); // ResponseStatusException 是 RuntimeException
            }
        } catch (RuntimeException exception) { // 任何主要業務流程中的 RuntimeException 都會走這裡
            if(redisStockDeducted){
                redisStockService.restoreStock(campaignId, request.getQuantity());
            }

            // Idempotency 標記 FAILED
            redisIdempotencyService.markFailed(
                    campaignId,
                    userId,
                    idempotencyKey,
                    exception.getMessage()
            );

            throw exception;
        }
    }
}