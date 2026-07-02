// 驗證的問題：idempotency key + DB unique constraint 是否有效防止同一 user 重複搶購
//
// 三種重複場景：
// 1. 連點 / 前端 retry / 網路 timeout 重送 → 同一 idempotency key 多次送出 → 200 (idempotent，回傳同一張訂單)
// 2. 同一 user 搶到後又送不同 key 的 request → 409 (DB unique(user_id, campaign_id) 阻擋)
//
// 測試設計：
// - 10 個 VU，每個 VU 對應一個簡化 userId (1~10)，來自 DataInitializer
// - 每個 VU 執行恰好 1 次 default function，流程如下：
//     Step 1: 第一次下單 (key-1) → 預期 200，建立新訂單
//     Step 2: 相同 key-1 再送 2 次 (模擬 retry) → 預期 200，idempotent 回傳同一訂單
//     Step 3: 換新 key-2，同 user 再搶同一 campaign → 預期 409，DB constraint 阻擋
//
// 驗收標準：
// - order_success = 10（每個 user 各建立 1 張訂單）
// - idempotent_hit = 20（10 VU × 2 次 retry）
// - order_conflict = 10（每個 user 第二把 key 都被 DB 擋住）
// - teardown: DB 訂單數增加 10，campaign stock 扣 10

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CAMPAIGN_ID = Number(__ENV.CAMPAIGN_ID || 1);
const NUM_USERS = 10; // DataInitializer 建立 10 個 user，id = 1~10

// 自訂 metrics
const orderCreateDuration = new Trend('order_create_duration', true);
const orderSuccess = new Counter('order_success');       // Step 1: 200 新訂單
const idempotentHit = new Counter('idempotent_hit');     // Step 2: 200 同 key retry
const orderConflict = new Counter('order_conflict');     // Step 3: 409 同 user 不同 key
const orderUnexpectedFailure = new Counter('order_unexpected_failure');

// 允許 200 和 409，不讓 k6 把 409 當 HTTP failure
http.setResponseCallback(http.expectedStatuses(200, 409));

export const options = {
    scenarios: {
        idempotency_test: {
            executor: 'per-vu-iterations',
            vus: NUM_USERS,
            iterations: 1, // 每個 VU 執行恰好 1 次
            maxDuration: '30s',
        },
    },
};

export function setup() {
    const campaignRes = http.get(`${BASE_URL}/campaigns/${CAMPAIGN_ID}`);
    check(campaignRes, { 'setup: campaign exists': (r) => r.status === 200 });

    const ordersRes = http.get(`${BASE_URL}/orders`);
    check(ordersRes, { 'setup: orders can be listed': (r) => r.status === 200 });

    const campaign = campaignRes.json();

    if (campaign.stock < NUM_USERS) {
        throw new Error(
            `Campaign ${CAMPAIGN_ID} stock (${campaign.stock}) must be >= ${NUM_USERS}; reset DB or pick another campaign`
        );
    }

    console.log(
        `[Setup] campaignId=${CAMPAIGN_ID}, stock=${campaign.stock}, initialOrders=${ordersRes.json().length}`
    );

    return {
        initialStock: campaign.stock,
        initialOrderCount: ordersRes.json().length,
    };
}

export default function () {
    // 簡化 userId：VU 1 = user 1，VU 2 = user 2，以此類推
    const userId = __VU;

    // 同一 VU 內的兩把 idempotency key
    const key1 = `vu-${__VU}-key-1`; // 第一把：用來測試 idempotency
    const key2 = `vu-${__VU}-key-2`; // 第二把：用來測試 user+campaign unique constraint

    const payload = JSON.stringify({ userId, quantity: 1 });
    const contentTypeHeader = { 'Content-Type': 'application/json' };

    function sendOrder(idempotencyKey) {
        const res = http.post(
            `${BASE_URL}/campaigns/${CAMPAIGN_ID}/orders`,
            payload,
            {
                headers: {
                    ...contentTypeHeader,
                    'Idempotency-Key': idempotencyKey,
                },
            }
        );
        orderCreateDuration.add(res.timings.duration);
        return res;
    }

    // ── Step 1：第一次下單，應建立新訂單 (200) ──────────────────────────────
    const res1 = sendOrder(key1);
    const step1Pass = check(res1, {
        '[Step 1] 第一次下單回傳 200': (r) => r.status === 200,
    });
    if (step1Pass) {
        orderSuccess.add(1);
    } else {
        orderUnexpectedFailure.add(1);
        console.error(`[VU ${__VU}] Step 1 failed: status=${res1.status}, body=${res1.body}`);
    }

    // ── Step 2：相同 key 再送 2 次，模擬 retry / 連點 (200 idempotent) ────────
    for (let i = 0; i < 2; i++) {
        const res = sendOrder(key1);
        const step2Pass = check(res, {
            '[Step 2] 相同 key retry 回傳 200': (r) => r.status === 200,
        });
        if (step2Pass) {
            idempotentHit.add(1);
        } else {
            orderUnexpectedFailure.add(1);
            console.error(`[VU ${__VU}] Step 2 retry ${i + 1} failed: status=${res.status}, body=${res.body}`);
        }
    }

    // ── Step 3：同 user 換新 key 再搶，DB unique(user_id, campaign_id) 應阻擋 (409) ──
    const res2 = sendOrder(key2);
    const step3Pass = check(res2, {
        '[Step 3] 同 user 不同 key 回傳 409': (r) => r.status === 409,
    });
    if (step3Pass) {
        orderConflict.add(1);
    } else {
        orderUnexpectedFailure.add(1);
        console.error(`[VU ${__VU}] Step 3 failed: status=${res2.status}, body=${res2.body}`);
    }
}

export function teardown(data) {
    const campaignRes = http.get(`${BASE_URL}/campaigns/${CAMPAIGN_ID}`);
    const ordersRes = http.get(`${BASE_URL}/orders`);

    check(campaignRes, { 'teardown: campaign can be fetched': (r) => r.status === 200 });
    check(ordersRes, { 'teardown: orders can be listed': (r) => r.status === 200 });

    const campaign = campaignRes.json();
    const orders = ordersRes.json();
    const createdOrders = orders.length - data.initialOrderCount;
    const deductedStock = data.initialStock - campaign.stock;

    console.log(
        `[Teardown] initialStock=${data.initialStock}, finalStock=${campaign.stock}, ` +
        `createdOrders=${createdOrders}, deductedStock=${deductedStock}`
    );

    // 每個 user 只能成功 1 次 → 共 10 筆訂單
    check({ createdOrders }, {
        'DB 訂單數恰好增加 10 (每個 user 只有 1 筆)': (r) => r.createdOrders === NUM_USERS,
    });

    // 庫存扣減量 = 成功訂單數
    check({ createdOrders, deductedStock }, {
        'stock 扣減量等於成功訂單數': (r) => r.deductedStock === r.createdOrders,
    });
}
