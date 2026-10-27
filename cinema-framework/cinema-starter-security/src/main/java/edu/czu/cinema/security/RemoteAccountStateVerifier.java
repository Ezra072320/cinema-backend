package edu.czu.cinema.security;

import edu.czu.cinema.remote.AccountClient;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RemoteAccountStateVerifier implements AccountStateVerifier {
    private final AccountClient accounts;

    @Override
    public edu.czu.cinema.domain.admin.AccountAccess verify(String role, Long id, String pv) {
        try {
            var access = accounts.access(role, id, pv);
            if (access == null) throw new IllegalStateException("Missing account state");
            return access;
        } catch (RuntimeException e) {
            throw new edu.czu.cinema.common.exception.ServiceUnavailableException();
        }
    }

    public boolean isActive(String role, Long id, String pv) {
        try {
            return accounts.isActive(role, id, pv);
        } catch (RuntimeException e) {
            throw new edu.czu.cinema.common.exception.ServiceUnavailableException();
        }
    }
}
