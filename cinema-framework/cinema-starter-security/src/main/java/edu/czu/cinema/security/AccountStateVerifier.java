package edu.czu.cinema.security;

public interface AccountStateVerifier {
    boolean isActive(String role, Long id, String pwdVersion);

    default edu.czu.cinema.domain.admin.AccountAccess verify(String role, Long id, String pwdVersion) {
        return new edu.czu.cinema.domain.admin.AccountAccess(isActive(role, id, pwdVersion), java.util.Set.of());
    }
}
