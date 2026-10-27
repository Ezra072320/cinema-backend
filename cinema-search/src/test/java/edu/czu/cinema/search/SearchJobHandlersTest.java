package edu.czu.cinema.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import edu.czu.cinema.jobs.JobMutex;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchJobHandlersTest {
    JobMutex mutex() {
        var mutex=mock(JobMutex.class);
        when(mutex.run(anyString(),any())).thenAnswer(call -> { ((Runnable)call.getArgument(1)).run(); return true; });
        return mutex;
    }
    @Test void successFollowsTheAcceptedAliasAndDoesNotDuplicateTheWorker() {
        var index=mock(IndexCoordinator.class);
        when(index.beginRebuild()).thenReturn("attempt");
        when(index.state()).thenReturn(Map.of("status","READY","active_index","attempt","cursor_version",12L));
        new SearchJobHandlers(mutex(),index).rebuild();
        verify(index,never()).advance();
    }
    @Test void existingBuildIsLeftUntouched() {
        var index=mock(IndexCoordinator.class);
        new SearchJobHandlers(mutex(),index).rebuild();
        verify(index).beginRebuild();
        verify(index,never()).state();
    }
    @Test void pausedBuildFailsWithoutDiscardingItsCursor() {
        var index=mock(IndexCoordinator.class);
        when(index.beginRebuild()).thenReturn("attempt");
        when(index.state()).thenReturn(Map.of("status","BUILDING","building_index","attempt","active_index","old","last_error","IOException"));
        assertThatThrownBy(() -> new SearchJobHandlers(mutex(),index).rebuild()).hasMessageContaining("cursor retained");
        verify(index,times(1)).beginRebuild();
        verify(index,never()).advance();
    }
    @Test void interruptedDispatchDoesNotBeginARebuild() {
        var index=mock(IndexCoordinator.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new SearchJobHandlers(mutex(),index).rebuild()).hasMessageContaining("interrupted");
            verifyNoInteractions(index);
        } finally { Thread.interrupted(); }
    }
}
