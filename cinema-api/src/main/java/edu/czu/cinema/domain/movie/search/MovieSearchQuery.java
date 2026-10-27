package edu.czu.cinema.domain.movie.search;

public record MovieSearchQuery(
    String q,
    String genre,
    String area,
    Integer marketStatus,
    Boolean showing,
    int page,
    int size,
    String sort) {
  public MovieSearchQuery {
    q = q == null ? "" : q.trim();
    genre = genre == null ? "" : genre.trim();
    area = area == null ? "" : area.trim();
    sort = sort == null ? "relevance" : sort;
    if (q.length() > 100
        || genre.length() > 64
        || area.length() > 64
        || page < 1
        || size < 1
        || size > 50
        || (long) page * size > 10000
        || !java.util.Set.of("relevance", "score", "release").contains(sort)
        || marketStatus != null && (marketStatus < 0 || marketStatus > 2))
      throw new edu.czu.cinema.common.exception.BusinessException(
          edu.czu.cinema.common.api.ResultCode.BAD_REQUEST, "搜索条件无效，请缩短关键词或调整页码");
  }
}
