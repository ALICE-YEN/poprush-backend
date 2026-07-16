# Pop-Rush

限量商品搶購後端，使用 Spring Boot、PostgreSQL 與 Redis 處理訂單、庫存及冪等請求。

## Documentation

- [架構圖與閱讀順序](docs/architecture/README.md)
- [建立訂單請求時序](docs/architecture/order-creation-sequence.md)
- [Idempotency 狀態與 TTL](docs/architecture/idempotency-state.md)
- [Redis / PostgreSQL Transaction Boundary](docs/architecture/transaction-boundary.md)
