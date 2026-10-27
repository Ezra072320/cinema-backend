package edu.czu.cinema.domain.admin;

import java.util.Set;

/** Authoritative account state and current privileges, supplied only by Identity. */
public record AccountAccess(boolean active, Set<String> permissions) {
  public AccountAccess {
    permissions = active && permissions != null ? Set.copyOf(permissions) : Set.of();
  }
}
