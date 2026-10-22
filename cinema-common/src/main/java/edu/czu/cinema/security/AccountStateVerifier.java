package edu.czu.cinema.security;

public interface AccountStateVerifier {
    boolean isActive(String role, Long id, String pwdVersion);
}
