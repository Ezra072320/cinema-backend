package edu.czu.cinema.domain.movie.search;

import java.math.BigDecimal;
import java.util.List;

/** Catalog-owned, versioned search projection. Hidden documents are retained as tombstones. */
public record MovieDocument(
    long id,
    long version,
    boolean hidden,
    String title,
    String originalTitle,
    String summary,
    String area,
    String language,
    String releaseDate,
    Integer runtimeMin,
    BigDecimal score,
    String posterUrl,
    String cat,
    String dir,
    Integer marketStatus,
    List<String> aliases,
    List<String> people) {
  public MovieDocument {
    if (id <= 0 || version <= 0 || title == null)
      throw new IllegalArgumentException("Invalid movie document");
    aliases = aliases == null ? List.of() : List.copyOf(aliases);
    people = people == null ? List.of() : List.copyOf(people);
  }
}
