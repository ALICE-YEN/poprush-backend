// 驗證的問題：Pessimistic Lock / SELECT FOR UPDATE 是否讓同一商品的扣庫存流程被序列化，避免超賣。
// 觀察重點：
// 1. 是否還會超賣：createdOrders <= initialStock，且扣掉的庫存量要等於成功訂單量。
// 2. response time 是否變慢：看 order_create_duration 的 avg / p95 / max。
// 3. request 是否排隊：同商品被 lock 序列化時，後面的 request 會等待前面的 transaction，通常會反映在 p95 / max 明顯高於 min / med。
import http from 'k6/http'; // 用來送 GET / POST request
import { check } from 'k6'; // 用來驗證結果是否符合預期
import { Counter, Trend } from 'k6/metrics'; // Counter：計數器，例如成功幾次、庫存不足幾次。Trend：紀錄時間，例如 API 花多久

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const QUANTITY = Number(__ENV.QUANTITY || 1);
const VUS = Number(__ENV.VUS || 50);
const ITERATIONS = Number(__ENV.ITERATIONS || 50);

// 自訂 metrics
const orderCreateDuration = new Trend('order_create_duration', true);
const orderSuccess = new Counter('order_success');
const orderOutOfStock = new Counter('order_out_of_stock');
const orderUnexpectedFailure = new Counter('order_unexpected_failure');

http.setResponseCallback(http.expectedStatuses(200, 400)); // 允許 200 和 400，不要讓 k6 把 400 當成 HTTP failure

export const options = {
    vus: VUS, // VUS 個虛擬使用者
    iterations: ITERATIONS, // 總共執行 ITERATIONS 次 default function
}; // 50 個人同時各打一筆 request

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
    const requestedQuantity = ITERATIONS * QUANTITY;

    if (product.stock < QUANTITY) {
        throw new Error(`Product ${PRODUCT_ID} stock must be at least ${QUANTITY}; current stock is ${product.stock}`);
    }

    console.log(`Pessimistic lock rush test: productId=${PRODUCT_ID}, initialStock=${product.stock}, requestCount=${ITERATIONS}, requestedQuantity=${requestedQuantity}`);

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

    orderCreateDuration.add(res.timings.duration);

    if (res.status === 200) {
        orderSuccess.add(1);
    } else if (res.status === 400) {
        orderOutOfStock.add(1);
    } else {
        orderUnexpectedFailure.add(1);
    }

    check(res, {
        'status is 200 or 400': (r) => r.status === 200 || r.status === 400,
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
    const expectedSuccessfulOrders = Math.min(Math.floor(data.initialStock / QUANTITY), ITERATIONS);

    console.log(`initialStock=${data.initialStock}, finalStock=${product.stock}, createdOrders=${createdOrders}, deductedStock=${deductedStock}, expectedSuccessfulOrders=${expectedSuccessfulOrders}`);
    console.log('Queue signal: compare order_create_duration min/med vs p95/max in the k6 summary. A much higher p95/max means requests waited behind the product row lock.');

    check({ createdOrders, deductedStock, quantity: QUANTITY }, {
        'created orders match deducted stock': (r) => r.createdOrders * r.quantity === r.deductedStock,
    });

    check({ createdOrders, initialStock: data.initialStock, quantity: QUANTITY }, {
        'created orders do not exceed initial stock': (r) => r.createdOrders * r.quantity <= r.initialStock,
    });

    check({ createdOrders, expectedSuccessfulOrders }, {
        'successful orders match available stock or request count': (r) => r.createdOrders === r.expectedSuccessfulOrders,
    });
}
