package edu.czu.cinema.domain.movie.search;

public record MovieChanged(String eventId, int schemaVersion, MovieDocument document) {
  public MovieChanged {
    if (eventId == null
        || eventId.isBlank()
        || eventId.length() > 64
        || schemaVersion != 1
        || document == null) throw new IllegalArgumentException("Invalid movie event");
  }
}
