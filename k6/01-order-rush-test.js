import http from 'k6/http';
import { check } from 'k6';

// 用 50 個虛擬使用者，總共送出 50 次 request
export const options = {
    vus: 50, // virtual users
    iterations: 50, // 總共執行 50 次
};

export default function () {
    const payload = JSON.stringify({
        productId: 1,
        quantity: 1,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };

    const res = http.post('http://localhost:8080/orders', payload, params);

    console.log(`[VU ${__VU} ITER ${__ITER}] status=${res.status} body=${res.body}`);

    check(res, {
        'status is 200 or 500': (r) => r.status === 200 || r.status === 500,
    });
}
