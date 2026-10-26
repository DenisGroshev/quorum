#!/usr/bin/env node
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const USER_COUNT = parseInt(process.env.USER_COUNT || "200", 10);
const MOVIE_COUNT = parseInt(process.env.MOVIE_COUNT || "300", 10);
const SESSION_COUNT = parseInt(process.env.SESSION_COUNT || "50", 10);
const OUT_DIR = process.argv[2] || process.env.OUT_DIR || ".";
const JWT_SECRET = process.env.JWT_SECRET;
const BASE_URL = process.env.BASE_URL || "http://localhost:8080";

if (!JWT_SECRET || JWT_SECRET.length < 32 || JWT_SECRET.length > 47) {
    console.error("JWT_SECRET must be 32-47 bytes (ASCII chars) so JwtService's Keys.hmacShaKeyFor picks HS256.");
    process.exit(1);
}

function base64url(buf) {
    return buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function signAccessToken(userId, tokenVer) {
    const now = Math.floor(Date.now() / 1000);
    const header = { alg: "HS256", typ: "JWT" };
    const payload = { sub: userId, tokenVer, iat: now, exp: now + 6 * 3600 };
    const signingInput = `${base64url(Buffer.from(JSON.stringify(header)))}.${base64url(Buffer.from(JSON.stringify(payload)))}`;
    const sig = crypto.createHmac("sha256", JWT_SECRET).update(signingInput).digest();
    return `${signingInput}.${base64url(sig)}`;
}

function uuid() {
    return crypto.randomUUID();
}

function randInt(min, max) {
    return min + Math.floor(Math.random() * (max - min + 1));
}

function pick(arr) {
    return arr[randInt(0, arr.length - 1)];
}

function sample(arr, n) {
    const copy = arr.slice();
    for (let i = copy.length - 1; i > 0; i--) {
        const j = randInt(0, i);
        [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy.slice(0, Math.min(n, copy.length));
}

function inviteCode() {
    const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let s = "";
    for (let i = 0; i < 10; i++) s += chars[randInt(0, chars.length - 1)];
    return s;
}

function sqlStr(s) {
    return `'${String(s).replace(/'/g, "''")}'`;
}

const sql = [];
const users = [];
const tokens = [];

for (let i = 0; i < USER_COUNT; i++) {
    const id = uuid();
    const tgUserId = 900_000_000 + i;
    users.push({ id, tgUserId });
    tokens.push({ userId: id, accessToken: signAccessToken(id, 0) });
    sql.push(
        `INSERT INTO users (id, tg_user_id, display_name, username, token_ver, created_at) VALUES ` +
            `(${sqlStr(id)}, ${tgUserId}, ${sqlStr("Load User " + i)}, ${sqlStr("loaduser" + i)}, 0, now() - interval '${randInt(1, 60)} days');`,
    );
}

const movies = [];
for (let i = 0; i < MOVIE_COUNT; i++) {
    const tmdbId = 5_000_001 + i;
    movies.push(tmdbId);
    sql.push(
        `INSERT INTO movies (tmdb_id, original_title, vote_avg, vote_count, synced_at) VALUES ` +
            `(${tmdbId}, ${sqlStr("Load Test Movie " + i)}, ${(randInt(10, 95) / 10).toFixed(1)}, ${randInt(10, 5000)}, now());`,
    );
}

for (const user of users) {
    const count = randInt(50, 200);
    for (const tmdbId of sample(movies, count)) {
        sql.push(
            `INSERT INTO watchlist_items (user_id, tmdb_id, added_at) VALUES ` +
                `(${sqlStr(user.id)}, ${tmdbId}, now() - interval '${randInt(0, 60)} days') ON CONFLICT DO NOTHING;`,
        );
    }
}

// State mix roughly matching a healthy-but-not-perfect funnel: most sessions
// are either actively voting or settled; a handful never got that far.
const stateCounts = { LOBBY: 8, VOTING: 15, DECIDED: 7, WATCHED: 12, EXPIRED: 5, CANCELLED: 3 };
const sessionStates = Object.entries(stateCounts).flatMap(([state, n]) => Array(n).fill(state));
while (sessionStates.length < SESSION_COUNT) sessionStates.push("VOTING");
sessionStates.length = SESSION_COUNT;

const votingSessions = [];
const allSessions = [];

sessionStates.forEach((state, idx) => {
    const sessionId = uuid();
    const host = pick(users);
    const memberCount = randInt(1, 4);
    const members = [host, ...sample(users.filter((u) => u.id !== host.id), memberCount)];
    const deckSize = pick([10, 20, 30]);
    const needsDeck = state !== "LOBBY" && state !== "CANCELLED";
    const deck = needsDeck ? sample(movies, deckSize) : [];

    const votingStartedAt = needsDeck ? `now() - interval '${randInt(1, 5)} days'` : "NULL";
    const votingDeadlineAt = needsDeck
        ? state === "VOTING"
            ? `now() + interval '2 hours'`
            : `now() - interval '${randInt(1, 4)} days'`
        : "NULL";
    const decidedAt = ["DECIDED", "WATCHED"].includes(state) ? `now() - interval '${randInt(1, 3)} days'` : "NULL";
    const watchedAt = state === "WATCHED" ? `now() - interval '${randInt(0, 2)} days'` : "NULL";
    const winnerTmdbId = ["DECIDED", "WATCHED"].includes(state) ? deck[0] : "NULL";

    sql.push(
        `INSERT INTO sessions (id, host_id, title, state, invite_code, deck_size, vote_deadline_minutes, ` +
            `voting_started_at, voting_deadline_at, decided_at, watched_at, winner_tmdb_id, winner_contested, created_at) ` +
            `VALUES (${sqlStr(sessionId)}, ${sqlStr(host.id)}, ${sqlStr("Load Session " + idx)}, ${sqlStr(state)}, ` +
            `${sqlStr(inviteCode())}, ${deckSize}, ${pick([30, 120, 1440])}, ${votingStartedAt}, ${votingDeadlineAt}, ` +
            `${decidedAt}, ${watchedAt}, ${winnerTmdbId}, false, now() - interval '${randInt(1, 6)} days');`,
    );

    members.forEach((m, mi) => {
        sql.push(
            `INSERT INTO session_members (session_id, user_id, is_host, joined_at) VALUES ` +
                `(${sqlStr(sessionId)}, ${sqlStr(m.id)}, ${mi === 0 ? "true" : "false"}, now() - interval '${randInt(1, 6)} days');`,
        );
    });

    deck.forEach((tmdbId, position) => {
        sql.push(
            `INSERT INTO deck_items (session_id, tmdb_id, position, score) VALUES ` +
                `(${sqlStr(sessionId)}, ${tmdbId}, ${position}, 5.0);`,
        );
    });

    const record = { sessionId, state, memberIds: members.map((m) => m.id) };
    allSessions.push(record);
    if (state === "VOTING") {
        votingSessions.push({ ...record, voteTmdbId: deck[0] });
    }
});

fs.mkdirSync(OUT_DIR, { recursive: true });
fs.writeFileSync(path.join(OUT_DIR, "seed.sql"), sql.join("\n") + "\n");
fs.writeFileSync(
    path.join(OUT_DIR, "loadtest-data.json"),
    JSON.stringify(
        {
            baseUrl: BASE_URL,
            searchQueries: ["matrix", "inception", "dune", "batman", "spirited away"],
            users: tokens,
            sessions: allSessions,
            votingSessions,
        },
        null,
        2,
    ),
);

console.log(`Wrote ${sql.length} statements to ${path.join(OUT_DIR, "seed.sql")}`);
console.log(
    `Wrote ${tokens.length} tokens, ${allSessions.length} sessions (${votingSessions.length} voting) to ${path.join(OUT_DIR, "loadtest-data.json")}`,
);
