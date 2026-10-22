package edu.czu.cinema.domain.score;

import edu.czu.cinema.domain.score.service.ReviewPolicy;
import edu.czu.cinema.domain.schedule.entity.Schedule;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class ReviewPolicyTest {
    final LocalDateTime start = LocalDateTime.of(2026,10,20,18,0);
    Schedule schedule() {
        var s = new Schedule(); s.setStatus(1); s.setStartTime(start); s.setEndTime(start.plusMinutes(130)); return s;
    }
    @Test void requiresMovieToEndButDoesNotWaitForCleaning() {
        assertThat(ReviewPolicy.check(2,schedule(),100,start.plusMinutes(99)).allowed()).isFalse();
        assertThat(ReviewPolicy.check(2,schedule(),100,start.plusMinutes(100)).allowed()).isTrue();
        assertThat(ReviewPolicy.check(6,schedule(),100,start.plusMinutes(100)).allowed()).isTrue();
    }
    @Test void unknownRuntimeFallsBackConservativelyToScheduleEnd() {
        assertThat(ReviewPolicy.check(2,schedule(),0,start.plusMinutes(120)).allowed()).isFalse();
        assertThat(ReviewPolicy.check(2,schedule(),null,start.plusMinutes(130)).allowed()).isTrue();
    }
    @Test void excludesUnpaidRefundedClosedAndCancelled() {
        for (int status : new int[]{1,3,4})
            assertThat(ReviewPolicy.check(status,schedule(),100,start.plusDays(1)).allowed()).isFalse();
        var s = schedule(); s.setStatus(2);
        assertThat(ReviewPolicy.check(2,s,100,start.plusDays(1)).allowed()).isFalse();
        assertThat(ReviewPolicy.check(2,null,100,start.plusDays(1)).allowed()).isFalse();
    }
}
