package edu.czu.cinema.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.czu.cinema.common.exception.ServiceUnavailableException;
import edu.czu.cinema.remote.AccountClient;

import org.junit.jupiter.api.Test;

class RemoteAccountStateVerifierTest {
    @Test
    void infrastructureFailureIsNotReportedAsARevokedCredential() {
        var client = mock(AccountClient.class);
        when(client.isActive("USER", 7L, "version"))
                .thenThrow(new RuntimeException("connection failed"));
        assertThatThrownBy(
                        () ->
                                new RemoteAccountStateVerifier(client)
                                        .isActive("USER", 7L, "version"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void realRevocationStillRejectsTheToken() {
        var client = mock(AccountClient.class);
        when(client.isActive("USER", 7L, "version")).thenReturn(false);
        assertThat(new RemoteAccountStateVerifier(client).isActive("USER", 7L, "version"))
                .isFalse();
    }
}
