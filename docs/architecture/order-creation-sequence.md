# Order creation sequence

這張圖說明 `POST /campaigns/{campaignId}/orders` 中，idempotency、Redis 庫存與 PostgreSQL transaction 的互動。

```mermaid
sequenceDiagram
    autonumber

    actor Client
    participant API as CampaignController
    participant Service as OrderService
    participant Idem as Redis Idempotency
    participant Stock as Redis Stock
    participant DB as PostgreSQL

    Client->>API: POST /campaigns/{id}/orders
    API->>Service: createOrder(...)
    Service->>Idem: SET PROCESSING NX (TTL 30s)

    alt 未取得 Idempotency Key
        Idem-->>Service: 現有紀錄
        alt SUCCESS
            Service-->>Client: 直接返回第一次成功 response
        else PROCESSING
            Service-->>Client: 409 still processing
        else FAILED
            Service-->>Client: 409 與已儲存錯誤
        end
    else 取得 Idempotency Key
        Service->>DB: 查詢相同 idempotency 訂單

        alt DB 已有訂單
            Service->>Idem: SET SUCCESS (TTL 24h)
            Service-->>Client: 回傳已有訂單
        else DB 沒有訂單
            Service->>DB: 查詢 User 與 Campaign

            alt User/Campaign 不存在或 Campaign 時間無效
                Service->>Idem: SET FAILED (TTL 5m)
                Service-->>Client: 4xx error
            else Campaign 可購買
                Service->>Stock: Lua 原子檢查並扣庫存

                alt Redis 庫存不足
                    Service->>Idem: SET FAILED (TTL 5m)
                    Service-->>Client: 400 Not enough stock
                else Redis 扣庫存成功
                    Service->>DB: 扣 DB 庫存
                    Service->>DB: INSERT Order

                    alt Transaction commit
                        Service->>Idem: afterCommit: SET SUCCESS (TTL 24h)
                        Service-->>Client: CreateOrderResponse
                    else Transaction rollback
                        Service->>Stock: afterCompletion: 補回庫存
                        Service->>Idem: SET FAILED (TTL 5m)
                        Service-->>Client: error response
                    end
                end
            end
        end
    end
```

## 關鍵觀察

- Idempotency key 在查詢 User 和 Campaign 之前就會建立。
- Campaign 已結束時，PROCESSING 會被改寫為 FAILED，FAILED 只保留 5 分鐘。
- Redis 庫存先扣，PostgreSQL 後扣；後續失敗時必須由應用程式補回 Redis 庫存。
- `afterCommit` 只在 DB 確實 commit 後將 idempotency 標記為 SUCCESS。
