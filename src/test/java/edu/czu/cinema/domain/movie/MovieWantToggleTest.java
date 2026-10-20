package edu.czu.cinema.domain.movie;

import edu.czu.cinema.domain.movie.entity.UserWant;
import edu.czu.cinema.domain.movie.mapper.*;
import edu.czu.cinema.domain.movie.service.impl.MovieServiceImpl;
import edu.czu.cinema.domain.schedule.mapper.ScheduleMapper;
import edu.czu.cinema.domain.score.mapper.ScoreMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 想看开关的返回值语义 —— 「切换后的真实状态」, 不是「操作成功」.
 *
 * <p>回归背景(2026-10-19): handleWant 早先两个分支都 {@code return true},
 * 前端 WantButton 拿它驱动提示文案与按钮回显, 于是取消想看后仍弹
 * 「已加入想看」且按钮停在「已想看」不回退.
 */
class MovieWantToggleTest {

    private MovieServiceImpl service(UserWantMapper wantMapper) {
        return new MovieServiceImpl(mock(MovieMapper.class), mock(TypeMapper.class),
                mock(MovieTypeBindMapper.class), wantMapper, mock(ScoreMapper.class),
                mock(PersonMapper.class), mock(MovieCrewMapper.class), mock(MovieImageMapper.class),
                mock(MovieVideoMapper.class), mock(MovieTagMapper.class), mock(MovieAltTitleMapper.class),
                mock(ScheduleMapper.class));
    }

    @Test
    void addWantReturnsTrue() {
        UserWantMapper wantMapper = mock(UserWantMapper.class);
        when(wantMapper.delete(any())).thenReturn(0); // 之前没想看 → 本次是加入

        assertThat(service(wantMapper).handleWant(1L, 2L)).isTrue();
        verify(wantMapper).insert(any(UserWant.class));
    }

    @Test
    void cancelWantReturnsFalse() {
        UserWantMapper wantMapper = mock(UserWantMapper.class);
        when(wantMapper.delete(any())).thenReturn(1); // 删到行 → 本次是取消

        assertThat(service(wantMapper).handleWant(1L, 2L)).isFalse();
        verify(wantMapper, never()).insert(any(UserWant.class));
    }
}
