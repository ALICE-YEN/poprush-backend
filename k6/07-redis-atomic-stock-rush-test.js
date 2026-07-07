// 驗證的問題：Redis Lua script atomic 扣庫存是否在高併發搶購下防止超賣，以及 DB 後續失敗時 Redis 補償是否正確還原庫存。
//
// 測試設計：
// - 50 個 VU，userId 循環使用 user 1~10（DataInitializer 建立），每個 VU 使用唯一 idempotency key
// - campaign 預設使用 id=1（stock=50，目前進行中）
// - 因為 DB unique(user_id, campaign_id)，每個 user 只能成功一次，其餘 409 + Redis 補償還原庫存
//
// 觀察重點：
// 1. 不超賣：createdOrders * QUANTITY === deductedStock，且 deductedStock <= initialStock
// 2. Redis 補償機制：teardown 送一個帶 X-Test-Fail-After-Stock-Deduct: true 的 request → 500，
//    之後驗證 DB campaign stock 沒有下降（@Transactional rollback）、Redis stock 沒有下降（restoreStock 補回）
// 3. 速度對比：比較 order_create_duration p95/max 與 05-order-pessimistic-lock-rush-test.js；
//    Redis atomic 不需要 DB row lock，p95/max 應明顯低於悲觀鎖版本

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CAMPAIGN_ID = Number(__ENV.CAMPAIGN_ID || 1);
const QUANTITY = Number(__ENV.QUANTITY || 1);
const VUS = Number(__ENV.VUS || 50); // 虛擬使用者數量
const ITERATIONS = Number(__ENV.ITERATIONS || 50); // 總共送 50 次
const NUM_USERS = 10; // DataInitializer 建立 10 個 user，id = 1~10

// 自訂 metrics
const orderCreateDuration = new Trend('order_create_duration', true);
const orderSuccess = new Counter('order_success');           // 200 新訂單
const orderOutOfStock = new Counter('order_out_of_stock');   // 400 Redis 庫存不足
const orderConflict = new Counter('order_conflict');         // 409 DB unique(user_id, campaign_id) 阻擋
const orderUnexpectedFailure = new Counter('order_unexpected_failure');

// 允許 200、400、409，不讓 k6 把非 2xx 當 HTTP failure
http.setResponseCallback(http.expectedStatuses(200, 400, 409));

export const options = {
    vus: VUS,
    iterations: ITERATIONS,
};

export function setup() {
    const campaignRes = http.get(`${BASE_URL}/campaigns/${CAMPAIGN_ID}`);
    check(campaignRes, { 'setup: campaign exists': (r) => r.status === 200 });

    const ordersRes = http.get(`${BASE_URL}/orders`);
    check(ordersRes, { 'setup: orders can be listed': (r) => r.status === 200 });

    const campaign = campaignRes.json();

    if (campaign.stock < QUANTITY) {
        throw new Error(
            `Campaign ${CAMPAIGN_ID} stock (${campaign.stock}) must be >= ${QUANTITY}; reset DB or pick another campaign`
        );
    }

    console.log(
        `[Setup] Redis atomic rush test: campaignId=${CAMPAIGN_ID}, initialStock=${campaign.stock}, ` +
        `VUs=${VUS}, iterations=${ITERATIONS}, numUsers=${NUM_USERS}`
    );

    return {
        initialStock: campaign.stock,
        initialOrderCount: ordersRes.json().length,
    };
}

export default function () {
    // userId 循環 1~10，每個 VU + iteration 都有唯一 idempotency key，不走 idempotency 快取路徑
    const userId = ((__VU - 1) % NUM_USERS) + 1;
    const idempotencyKey = `vu-${__VU}-iter-${__ITER}-redis`;

    const payload = JSON.stringify({ userId, quantity: QUANTITY });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
        },
    };

    const res = http.post(`${BASE_URL}/campaigns/${CAMPAIGN_ID}/orders`, payload, params);

    orderCreateDuration.add(res.timings.duration);

    if (res.status === 200) {
        orderSuccess.add(1);
    } else if (res.status === 400) {
        orderOutOfStock.add(1);
    } else if (res.status === 409) {
        // DB unique(user_id, campaign_id) 阻擋；OrderService 會呼叫 restoreStock 把 Redis 庫存還回去
        orderConflict.add(1);
    } else {
        orderUnexpectedFailure.add(1);
    }

    check(res, {
        'status is 200, 400, or 409': (r) => r.status === 200 || r.status === 400 || r.status === 409,
    });
}

export function teardown(data) {
    // ── Step 1：Redis 補償機制驗證 ───────────────────────────────────────────────
    // 在主要驗收前，先確認「DB 後續失敗 → Redis 庫存被補回」這條補償路徑是否正常
    const campaignBeforeRollbackRes = http.get(`${BASE_URL}/campaigns/${CAMPAIGN_ID}`);
    check(campaignBeforeRollbackRes, { 'teardown: campaign can be fetched': (r) => r.status === 200 });

    const campaignBeforeRollback = campaignBeforeRollbackRes.json();
    const stockBeforeRollback = campaignBeforeRollback.stock;

    if (stockBeforeRollback >= QUANTITY) {
        // 送一個模擬「Redis 扣完庫存後 DB 操作失敗」的 request
        // OrderService 的 catch block 會呼叫 redisStockService.restoreStock() 把庫存補回去
        // @Transactional 也會確保 DB stock 沒有被扣掉
        const rollbackRes = http.post(
            `${BASE_URL}/campaigns/${CAMPAIGN_ID}/orders`,
            JSON.stringify({ userId: 1, quantity: QUANTITY }),
            {
                headers: {
                    'Content-Type': 'application/json',
                    'Idempotency-Key': `rollback-test-${Date.now()}`,
                    'X-Test-Fail-After-Stock-Deduct': 'true',
                },
            }
        );

        check(rollbackRes, {
            'rollback test: forced failure returns 500': (r) => r.status === 500,
        });

        const campaignAfterRollbackRes = http.get(`${BASE_URL}/campaigns/${CAMPAIGN_ID}`);
        const campaignAfterRollback = campaignAfterRollbackRes.json();

        console.log(
            `[Teardown] Rollback check: stockBefore=${stockBeforeRollback}, stockAfter=${campaignAfterRollback.stock} ` +
            `(should be equal: DB @Transactional rollback + Redis restoreStock)`
        );

        check({ stockBeforeRollback, stockAfterRollback: campaignAfterRollback.stock }, {
            'rollback test: DB stock restored by @Transactional': (r) => r.stockAfterRollback === r.stockBeforeRollback,
        });
    } else {
        console.log('[Teardown] Skipping rollback test: no remaining stock after rush');
    }

    // ── Step 2：主要驗收 ─────────────────────────────────────────────────────────
    const finalCampaignRes = http.get(`${BASE_URL}/campaigns/${CAMPAIGN_ID}`);
    const ordersRes = http.get(`${BASE_URL}/orders`);

    check(finalCampaignRes, { 'teardown: final campaign can be fetched': (r) => r.status === 200 });
    check(ordersRes, { 'teardown: final orders can be listed': (r) => r.status === 200 });

    const campaign = finalCampaignRes.json();
    const orders = ordersRes.json();
    const createdOrders = orders.length - data.initialOrderCount;
    const deductedStock = data.initialStock - campaign.stock;
    // 成功訂單上限 = min(庫存 / 每次購買量, unique user 數量, iterations 總次數)
    const expectedSuccessfulOrders = Math.min(
        Math.floor(data.initialStock / QUANTITY),
        NUM_USERS,
        ITERATIONS
    );

    console.log(
        `[Teardown] initialStock=${data.initialStock}, finalStock=${campaign.stock}, ` +
        `createdOrders=${createdOrders}, deductedStock=${deductedStock}, expectedMax=${expectedSuccessfulOrders}`
    );
    console.log(
        'Speed check: compare order_create_duration p95/max vs 05-order-pessimistic-lock-rush-test.js. ' +
        'Redis atomic (no DB row lock) should show lower p95/max.'
    );

    // Redis 409 補償路徑：每次 409 都會呼叫 restoreStock，所以 DB 庫存扣減量應精準等於成功訂單量
    check({ createdOrders, deductedStock, quantity: QUANTITY }, {
        'created orders match deducted stock (Redis compensation works)': (r) =>
            r.createdOrders * r.quantity === r.deductedStock,
    });

    check({ createdOrders, initialStock: data.initialStock, quantity: QUANTITY }, {
        'created orders do not exceed initial stock (no oversell)': (r) =>
            r.createdOrders * r.quantity <= r.initialStock,
    });

    check({ createdOrders, expectedSuccessfulOrders }, {
        'successful orders match min(stock, unique users, iterations)': (r) =>
            r.createdOrders === r.expectedSuccessfulOrders,
    });
}
