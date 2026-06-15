import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const QUANTITY = Number(__ENV.QUANTITY || 1);

export const options = {
    vus: 1,
    iterations: 1,
};

// 記錄測試前狀態
export function setup() {
    const productRes = http.get(`${BASE_URL}/products/${PRODUCT_ID}`);
    const ordersRes = http.get(`${BASE_URL}/orders`);

    check(productRes, {
        'setup product exists': (r) => r.status === 200,
    });

    check(ordersRes, {
        'setup orders can be listed': (r) => r.status === 200,
    });

    const product = productRes.json();
    const orders = ordersRes.json();

    // 保存初始狀態，這份資料會傳給 teardown(data)
    return {
        initialStock: product.stock,
        initialOrderCount: orders.length,
    };
}

// 真正執行的測試內容，送出故意失敗的下單請求
export default function () {
    const payload = JSON.stringify({
        productId: PRODUCT_ID,
        quantity: QUANTITY,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-Test-Fail-After-Stock-Deduct': 'true',
        },
    };

    const res = http.post(`${BASE_URL}/orders`, payload, params);

    check(res, {
        'midway failure returns 500': (r) => r.status === 500,
    });
}

// 驗證資料是否變髒
export function teardown(data) {
    const productRes = http.get(`${BASE_URL}/products/${PRODUCT_ID}`);
    const ordersRes = http.get(`${BASE_URL}/orders`);

    const product = productRes.json();
    const orders = ordersRes.json();

    check(productRes, {
        'final product can be fetched': (r) => r.status === 200,
    });

    check(ordersRes, {
        'final orders can be listed': (r) => r.status === 200,
    });

    check(product, {
        'stock was deducted even though order failed': (p) => p.stock === data.initialStock - QUANTITY,
    });

    check(orders, {
        'order was not created after midway failure': (o) => o.length === data.initialOrderCount,
    });
}
