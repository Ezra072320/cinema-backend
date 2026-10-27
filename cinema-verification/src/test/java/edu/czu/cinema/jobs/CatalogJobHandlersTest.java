package edu.czu.cinema.jobs;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import edu.czu.cinema.domain.movie.sync.MovieSyncService;
import edu.czu.cinema.domain.movie.sync.MovieSyncStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogJobHandlersTest {
    JobMutex mutex() {
        var mutex=mock(JobMutex.class);
        when(mutex.run(anyString(),any())).thenAnswer(call -> { ((Runnable)call.getArgument(1)).run(); return true; });
        return mutex;
    }
    MovieSyncStatus completed(int failures) {
        return new MovieSyncStatus(false,1,1,failures==0?1:0,failures,null,1,2,List.of(),0,0,1,0,0,"finished","COMPLETED","SYNC");
    }
    @Test void completedWorkerIsRequiredForSuccess() {
        var sync=mock(MovieSyncService.class);
        when(sync.start(1)).thenReturn(true);
        when(sync.status()).thenReturn(completed(0));
        new CatalogJobHandlers(mutex(),sync).upcoming();
        verify(sync).start(1);
        verify(sync).status();
    }
    @Test void partialFailureProducesFailedCallback() {
        var sync=mock(MovieSyncService.class);
        when(sync.start(1)).thenReturn(true);
        when(sync.status()).thenReturn(completed(1));
        assertThatThrownBy(() -> new CatalogJobHandlers(mutex(),sync).upcoming()).hasMessageContaining("failed items");
    }
    @Test void manualSyncBusyDoesNotStartOrMonitorAnUnrelatedAttempt() {
        var sync=mock(MovieSyncService.class);
        new CatalogJobHandlers(mutex(),sync).upcoming();
        verify(sync).start(1);
        verify(sync,never()).status();
    }
    @Test void boundedParametersRejectSilentFullLibrarySync() {
        assertThat(CatalogJobHandlers.batchSize(null)).isEqualTo(1);
        assertThat(CatalogJobHandlers.batchSize(" 50 ")).isEqualTo(50);
        for (var value:List.of("0","51","-1","all","1.5","1,2"))
            assertThatThrownBy(() -> CatalogJobHandlers.batchSize(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
