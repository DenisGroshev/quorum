package app.quorum.web;

import java.util.Set;

public record ReplaceFavoriteGenresRequest(Set<Integer> genreIds) {}
