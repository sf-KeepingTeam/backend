import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, getTestHeaders } from './common.js';

export const options = {
    scenarios: {
        background: {
            executor: 'constant-vus',
            vus: 50,
            duration: '5m',
        }
    },
    thresholds: {
        http_req_duration: ['p(95)<500'],
        http_req_failed: ['rate<0.01'],
    }
};

const STORE_IDS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20];

export default function() {
    const storeId = STORE_IDS[Math.floor(Math.random() * STORE_IDS.length)];
    const customerId = (__VU % 100) + 1;
    const headers = getTestHeaders(customerId, 'CUSTOMER');

    const storeRes = http.get(`${BASE_URL}/stores/${storeId}`, { headers });
    check(storeRes, { 'store: status 200': (r) => r.status === 200 });

    sleep(0.5);

    const menuRes = http.get(`${BASE_URL}/stores/${storeId}/menus`, { headers });
    check(menuRes, { 'menu: status 200': (r) => r.status === 200 });

    sleep(1);
}
