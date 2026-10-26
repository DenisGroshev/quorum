#!/usr/bin/env node

const http = require("http");
const url = require("url");

const PORT = parseInt(process.env.PORT || "9999", 10);

function movieShort(id) {
    return {
        id,
        title: `Stub Movie ${id}`,
        original_title: `Stub Movie ${id}`,
        overview: "Load test stub result.",
        poster_path: "/stub.jpg",
        release_date: "2020-01-01",
        genre_ids: [28, 35],
        original_language: "en",
        vote_average: 7.2,
        vote_count: 500,
    };
}

function searchResponse() {
    const results = Array.from({ length: 10 }, (_, i) => movieShort(5_000_001 + i));
    return { page: 1, results, total_pages: 1, total_results: results.length };
}

const server = http.createServer((req, res) => {
    const { pathname, query } = url.parse(req.url, true);
    res.setHeader("Content-Type", "application/json");

    if (pathname === "/configuration") {
        res.end(
            JSON.stringify({
                images: {
                    secure_base_url: "https://stub.image.tmdb.org/t/p/",
                    poster_sizes: ["w185", "w500"],
                },
            }),
        );
        return;
    }
    if (pathname === "/genre/movie/list") {
        res.end(
            JSON.stringify({
                genres: [
                    { id: 28, name: "Action" },
                    { id: 35, name: "Comedy" },
                    { id: 18, name: "Drama" },
                ],
            }),
        );
        return;
    }
    if (pathname === "/search/movie" || pathname === "/trending/movie/week") {
        res.end(JSON.stringify(searchResponse()));
        return;
    }
    const movieMatch = pathname.match(/^\/movie\/(\d+)$/);
    if (movieMatch) {
        const id = parseInt(movieMatch[1], 10);
        res.end(
            JSON.stringify({
                id,
                title: `Stub Movie ${id}`,
                original_title: `Stub Movie ${id}`,
                overview: "Load test stub result.",
                poster_path: "/stub.jpg",
                release_date: "2020-01-01",
                runtime: 110,
                genres: [{ id: 28, name: "Action" }],
                original_language: "en",
                vote_average: 7.2,
                vote_count: 500,
            }),
        );
        return;
    }

    res.statusCode = 404;
    res.end(JSON.stringify({ error: "not stubbed", pathname, query }));
});

server.listen(PORT, () => console.log(`TMDB stub listening on :${PORT}`));
