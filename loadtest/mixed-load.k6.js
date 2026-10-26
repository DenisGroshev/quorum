import http from "k6/http";
import { check } from "k6";
import { Rate } from "k6/metrics";

const DATA_FILE = __ENV.DATA_FILE || "./loadtest-data.json";
const data = JSON.parse(open(DATA_FILE));
const BASE_URL = __ENV.BASE_URL || data.baseUrl;
const RATE = parseInt(__ENV.RATE || "50", 10);
const DURATION = __ENV.DURATION || "3m";

const errorRate = new Rate("errors");

export const options = {
    scenarios: {
        mixed: {
            executor: "constant-arrival-rate",
            rate: RATE,
            timeUnit: "1s",
            duration: DURATION,
            preAllocatedVUs: RATE * 2,
            maxVUs: RATE * 4,
        },
    },
    thresholds: {
        http_req_failed: ["rate<0.001"],
        http_req_duration: ["p(95)<1000"],
    },
};

function pick(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function authHeaders(token) {
    return { headers: { Authorization: `Bearer ${token}` } };
}

function pollLobby() {
    const session = pick(data.sessions);
    const userId = pick(session.memberIds);
    const user = data.users.find((u) => u.userId === userId);
    const res = http.get(`${BASE_URL}/api/v1/sessions/${session.sessionId}`, authHeaders(user.accessToken));
    check(res, { "poll: 200": (r) => r.status === 200 }) || errorRate.add(1);
}

function searchMovies() {
    const user = pick(data.users);
    const query = pick(data.searchQueries);
    const res = http.get(
        `${BASE_URL}/api/v1/movies/search?query=${encodeURIComponent(query)}`,
        authHeaders(user.accessToken),
    );
    check(res, { "search: 200": (r) => r.status === 200 }) || errorRate.add(1);
}

function castVote() {
    if (data.votingSessions.length === 0) return;
    const session = pick(data.votingSessions);
    const userId = pick(session.memberIds);
    const user = data.users.find((u) => u.userId === userId);
    const res = http.put(
        `${BASE_URL}/api/v1/sessions/${session.sessionId}/votes/${session.voteTmdbId}`,
        JSON.stringify({ value: "LIKE" }),
        { headers: { Authorization: `Bearer ${user.accessToken}`, "Content-Type": "application/json" } },
    );
    check(res, { "vote: 204": (r) => r.status === 204 }) || errorRate.add(1);
}

function myWatchlist() {
    const user = pick(data.users);
    const res = http.get(`${BASE_URL}/api/v1/watchlist`, authHeaders(user.accessToken));
    check(res, { "watchlist: 200": (r) => r.status === 200 }) || errorRate.add(1);
}

function myActiveSessions() {
    const user = pick(data.users);
    const res = http.get(`${BASE_URL}/api/v1/sessions`, authHeaders(user.accessToken));
    check(res, { "sessions list: 200": (r) => r.status === 200 }) || errorRate.add(1);
}

export default function () {
    const roll = Math.random();
    if (roll < 0.5) pollLobby();
    else if (roll < 0.65) searchMovies();
    else if (roll < 0.8) castVote();
    else if (roll < 0.9) myWatchlist();
    else myActiveSessions();
}
