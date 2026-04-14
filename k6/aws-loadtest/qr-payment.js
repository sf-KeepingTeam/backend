import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, getTestHeaders } from './common.js';

const qrCreateTime = new Trend('qr_create_time');
const intentTime = new Trend('intent_time');
const approveTime = new Trend('approve_time');
const paymentFailures = new Counter('payment_failures');

export const options = {
    scenarios: {
        rampup: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 10 },
                { duration: '1m', target: 50 },
                { duration: '1m', target: 50 },
                { duration: '30s', target: 0 },
            ],
        }
    },
    thresholds: {
        'qr_create_time': ['p(95)<500'],
        'intent_time': ['p(95)<500'],
        'approve_time': ['p(95)<1000'],
        'http_req_failed': ['rate<0.05'],
    }
};

export default function() {
    // Customer ID: 1~100, Store ID: 1~20, Wallet ID = Customer ID (동일)
    const customerId = (__VU % 100) + 1;
    const walletId = customerId;  // Wallet ID = Customer ID
    const storeId = ((__VU + __ITER) % 20) + 1;
    const menuId = (storeId - 1) * 5 + ((__ITER % 5) + 1);  // Store별 메뉴 ID (각 Store당 5개 메뉴)

    const customerHeaders = getTestHeaders(customerId, 'CUSTOMER');
    const ownerHeaders = getTestHeaders(storeId, 'OWNER');  // Owner ID는 Store와 연관

    group('QR Payment Flow', function() {
        // ========================================
        // 1. Customer: QR 토큰 생성
        // ========================================
        const qrStart = Date.now();
        const qrRes = http.post(`${BASE_URL}/api/qr`, JSON.stringify({
            walletId: walletId,
            bindStoreId: storeId
        }), { headers: customerHeaders });

        qrCreateTime.add(Date.now() - qrStart);

        const qrCheck = check(qrRes, {
            'QR Create: status 201': (r) => r.status === 201,
            'QR Create: has tokenId': (r) => {
                try {
                    const body = r.json();
                    return body.data && body.data.tokenId;
                } catch (e) {
                    return false;
                }
            },
        });

        if (!qrCheck) {
            paymentFailures.add(1);
            console.error(`QR Create failed: ${qrRes.status} - ${qrRes.body}`);
            return;
        }

        const tokenId = qrRes.json('data').tokenId;
        sleep(0.3);

        // ========================================
        // 2. Owner: 결제 시작 (Initiate)
        // ========================================
        const uuid = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });

        const intentHeaders = Object.assign({}, ownerHeaders, {
            'Idempotency-Key': uuid
        });

        const intentStart = Date.now();
        const intentRes = http.post(`${BASE_URL}/api/qr/cpqr/${tokenId}/initiate`, JSON.stringify({
            storeId: storeId,
            orderItems: [{ menuId: menuId, quantity: 1 }]
        }), { headers: intentHeaders });

        intentTime.add(Date.now() - intentStart);

        const intentCheck = check(intentRes, {
            'Intent: status 201': (r) => r.status === 201,
            'Intent: has intentId': (r) => {
                try {
                    const body = r.json();
                    return body.data && body.data.intentId;
                } catch (e) {
                    return false;
                }
            },
        });

        if (!intentCheck) {
            paymentFailures.add(1);
            console.error(`Intent failed: ${intentRes.status} - ${intentRes.body}`);
            return;
        }

        const intentId = intentRes.json('data').intentId;
        sleep(0.3);

        // ========================================
        // 3. Customer: 결제 승인 (Approve)
        // ========================================
        const approveUuid = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });

        const approveHeaders = Object.assign({}, customerHeaders, {
            'Idempotency-Key': approveUuid
        });

        const approveStart = Date.now();
        const approveRes = http.post(`${BASE_URL}/api/qr/payments/${intentId}/approve`, JSON.stringify({
            pin: '123456'
        }), { headers: approveHeaders });

        approveTime.add(Date.now() - approveStart);

        const approveCheck = check(approveRes, {
            'Approve: status 200': (r) => r.status === 200,
        });

        if (!approveCheck) {
            paymentFailures.add(1);
            console.error(`Approve failed: ${approveRes.status} - ${approveRes.body}`);
        }
    });

    sleep(1);
}
