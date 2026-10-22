package edu.czu.cinema.domain.movie.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

class DistributedMovieSyncTest {
    @Test
    @SuppressWarnings("unchecked")
    void anotherReplicaSeesRunningProgressBeforeTheFirstPublisherTick() throws Throwable {
        var shared = new ConcurrentHashMap<String, String>();
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(i -> shared.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        doAnswer(
                        i -> {
                            shared.put(i.getArgument(0), i.getArgument(1));
                            return null;
                        })
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenAnswer(i -> shared.get(i.getArgument(0)));
        when(redis.hasKey(anyString())).thenAnswer(i -> shared.containsKey(i.getArgument(0)));
        var first = mock(MovieSyncService.class);
        var second = mock(MovieSyncService.class);
        when(first.status())
                .thenReturn(
                        new MovieSyncStatus(
                                true,
                                10,
                                0,
                                0,
                                0,
                                null,
                                1000L,
                                0L,
                                List.of(),
                                0,
                                0,
                                0,
                                0,
                                10,
                                "loading",
                                "DISCOVERING",
                                "SYNC"));
        var firstRequest = mock(ProceedingJoinPoint.class);
        when(firstRequest.getTarget()).thenReturn(first);
        when(firstRequest.proceed()).thenReturn(true);
        var secondRequest = mock(ProceedingJoinPoint.class);
        when(secondRequest.getTarget()).thenReturn(second);
        var owner = new DistributedMovieSync(redis, new ObjectMapper(), first);
        var observer = new DistributedMovieSync(redis, new ObjectMapper(), second);

        assertThat(owner.start(firstRequest)).isEqualTo(true);
        var observed = (MovieSyncStatus) observer.status(secondRequest);
        assertThat(observed.running()).isTrue();
        assertThat(observed.startedAt()).isEqualTo(1000L);
        assertThat(observer.start(secondRequest)).isEqualTo(false);
        verify(secondRequest, never()).proceed();
    }
}
