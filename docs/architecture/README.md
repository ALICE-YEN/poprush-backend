# Architecture diagrams

這裡的圖表使用 Mermaid 寫在 Markdown 內，目的是讓架構文件可以跟程式碼一起進行 Git diff、code review 與版本管理。

## 建議閱讀順序

1. [建立訂單請求時序](order-creation-sequence.md)：先理解 Client、Spring、Redis 與 PostgreSQL 的呼叫先後。
2. [Idempotency 狀態與 TTL](idempotency-state.md)：理解 PROCESSING、SUCCESS 與 FAILED 如何轉換和過期。
3. [Redis / PostgreSQL Transaction Boundary](transaction-boundary.md)：理解為什麼 DB rollback 不會自動撤銷 Redis，以及程式如何補償。

## 維護原則

- 圖表只表達跨元件的時序、狀態與一致性邊界，不複製每個 Java class 的細節。
- 修改 `OrderService`、`RedisIdempotencyService` 或 `RedisStockService` 的關鍵流程時，同一個 pull request 也要檢查這些圖。
- TTL、Redis key 格式與 HTTP status 必須以實際程式碼為準。
- 圖表應說明「為什麼」與「元件如何互動」，方法內的小細節留在程式碼。
