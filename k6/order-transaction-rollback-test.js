import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const QUANTITY = Number(__ENV.QUANTITY || 1);

export const options = {
    vus: 1,
    iterations: 1,
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
            'X-Test-Fail-After-Stock-Deduct': 'true',
        },
    };

    const res = http.post(`${BASE_URL}/orders`, payload, params);

    check(res, {
        'midway failure returns 500': (r) => r.status === 500,
    });
}

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
        'stock was rolled back after midway failure': (p) => p.stock === data.initialStock,
    });

    check(orders, {
        'order was not created after midway failure': (o) => o.length === data.initialOrderCount,
    });
}
