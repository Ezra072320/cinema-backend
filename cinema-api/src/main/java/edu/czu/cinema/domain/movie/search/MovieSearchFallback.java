package edu.czu.cinema.domain.movie.search;

import java.util.List;

public record MovieSearchFallback(MovieSearchQuery query, List<Long> showingIds) {}
