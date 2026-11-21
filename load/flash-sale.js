// k6 load test: a flash sale with far more buyers than units.
//
// Start the app with rate limiting off (the test hammers from one IP) and an admin account:
//   RATE_LIMIT_ENABLED=false ADMIN_EMAIL=admin@payflash.dev ADMIN_PASSWORD=admin-password-1
// Then run (Docker, no k6 install needed):
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8090 grafana/k6 run - < load/flash-sale.js
//
// Env: BASE_URL, ADMIN_EMAIL, ADMIN_PASSWORD, UNITS (default 500), BUYERS (default 2000),
//      VUS (default 500), DURATION (default 30s)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8090';
const UNITS = Number(__ENV.UNITS || 500);
const BUYERS = Number(__ENV.BUYERS || 2000);
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const granted = new Counter('units_granted');
const soldOut = new Counter('sold_out');
const duplicate = new Counter('already_reserved');
const unexpected = new Counter('unexpected_status');

// Only reserve calls use this: 202 (granted) and 409 (sold out / already reserved) are both correct answers.
const RESERVE_OK = http.expectedStatuses(202, 409);

export const options = {
  setupTimeout: '5m',
  scenarios: {
    rush: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 500),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    unexpected_status: ['count==0'],
    // Zero oversell: never more grants than units.
    units_granted: [`count<=${UNITS}`],
    'http_req_duration{name:reserve}': ['p(95)<500'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const admin = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    email: __ENV.ADMIN_EMAIL || 'admin@payflash.dev',
    password: __ENV.ADMIN_PASSWORD || 'admin-password-1',
  }), { headers: JSON_HEADERS });
  if (admin.status !== 200) throw new Error(`admin login failed: ${admin.status} ${admin.body}`);
  const adminToken = admin.json('accessToken');

  const now = Date.now();
  const sale = http.post(`${BASE}/api/v1/admin/sales`, JSON.stringify({
    name: `k6 drop ${now}`,
    description: 'Load test',
    price: 99,
    totalUnits: UNITS,
    startsAt: new Date(now - 1000).toISOString(),
    endsAt: new Date(now + 3600 * 1000).toISOString(),
    holdSeconds: 600,
  }), { headers: { ...JSON_HEADERS, Authorization: `Bearer ${adminToken}` } });
  if (sale.status !== 201) throw new Error(`create sale failed: ${sale.status} ${sale.body}`);

  // Sign the buyers up in parallel batches (BCrypt makes signup deliberately slow).
  const tokens = [];
  const run = Date.now();
  for (let i = 0; i < BUYERS; i += 100) {
    const batch = [];
    for (let j = i; j < Math.min(i + 100, BUYERS); j++) {
      batch.push(['POST', `${BASE}/api/v1/auth/signup`, JSON.stringify({
        email: `k6-${run}-${j}@load.test`, fullName: 'Load Buyer', password: 'password123', acceptTerms: true,
      }), { headers: JSON_HEADERS }]);
    }
    for (const res of http.batch(batch)) {
      if (res.status === 201) tokens.push(res.json('accessToken'));
    }
  }
  if (tokens.length < BUYERS) throw new Error(`only ${tokens.length}/${BUYERS} buyers signed up`);
  return { saleId: sale.json('id'), tokens };
}

export default function (data) {
  // Every request is a different buyer hammering "Buy now"; buyers keep retrying like real users.
  const token = data.tokens[exec.scenario.iterationInTest % data.tokens.length];
  const res = http.post(`${BASE}/api/v1/sales/${data.saleId}/reserve`, null, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'reserve' },
    responseCallback: RESERVE_OK,
  });
  if (res.status === 202) {
    granted.add(1);
  } else if (res.status === 409) {
    const code = res.json('code');
    if (code === 'SOLD_OUT') soldOut.add(1);
    else if (code === 'ALREADY_RESERVED') duplicate.add(1);
    else unexpected.add(1);
  } else {
    unexpected.add(1);
  }
  check(res, { 'reserve answered 202 or 409': (r) => r.status === 202 || r.status === 409 });
}

export function teardown(data) {
  const sale = http.get(`${BASE}/api/v1/sales/${data.saleId}`).json();
  console.log(`sale ${data.saleId}: ${sale.remainingUnits} of ${sale.totalUnits} units left`);
}
