// 驗證的問題：@Transactional 有讓「單一 request 內」扣庫存 + 建訂單一起 commit/rollback，但它沒有防止多個 request 同時讀到同一份舊庫存。Lost Update
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const QUANTITY = Number(__ENV.QUANTITY || 1);
const VUS = Number(__ENV.VUS || 50);
const ITERATIONS = Number(__ENV.ITERATIONS || 50);

export const options = {
    vus: VUS,
    iterations: ITERATIONS,
};

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

    if (product.stock < QUANTITY) {
        throw new Error(`Product ${PRODUCT_ID} stock must be at least ${QUANTITY}; current stock is ${product.stock}`);
    }

    return {
        initialStock: product.stock,
        initialOrderCount: orders.length,
    };
}

export default function () {
    const payload = JSON.stringify({
        productId: PRODUCT_ID,
        quantity: QUANTITY,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };

    const res = http.post(`${BASE_URL}/orders`, payload, params);

    check(res, {
        'status is 200 or 500': (r) => r.status === 200 || r.status === 500,
    });
}

export function teardown(data) {
    const productRes = http.get(`${BASE_URL}/products/${PRODUCT_ID}`);
    const ordersRes = http.get(`${BASE_URL}/orders`);

    check(productRes, {
        'final product can be fetched': (r) => r.status === 200,
    });

    check(ordersRes, {
        'final orders can be listed': (r) => r.status === 200,
    });

    const product = productRes.json();
    const orders = ordersRes.json();
    const createdOrders = orders.length - data.initialOrderCount;
    const deductedStock = data.initialStock - product.stock;

    console.log(`initialStock=${data.initialStock}, finalStock=${product.stock}, createdOrders=${createdOrders}, deductedStock=${deductedStock}`);

    check({ createdOrders, deductedStock }, {
        'created orders match deducted stock': (r) => r.createdOrders === r.deductedStock,
    });

    check({ createdOrders, initialStock: data.initialStock }, {
        'created orders do not exceed initial stock': (r) => r.createdOrders <= r.initialStock,
    });
}
