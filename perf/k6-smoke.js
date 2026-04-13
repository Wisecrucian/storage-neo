import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  vus: 20,
  duration: "2m",
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
  },
};

function randomInt(max) {
  return Math.floor(Math.random() * max);
}

export default function () {
  const payload = JSON.stringify({
    eventType: "k6_event",
    userId: `k6-user-${randomInt(10000)}`,
    sessionId: `k6-sess-${randomInt(1000000)}`,
    ipAddress: `10.0.${randomInt(255)}.${randomInt(255)}`,
    timestamp: new Date().toISOString(),
  });

  const res = http.post("http://localhost:8082/api/events/send?topic=events", payload, {
    headers: { "Content-Type": "application/json" },
  });

  check(res, {
    "status is 200": (r) => r.status === 200,
  });
  sleep(0.1);
}
