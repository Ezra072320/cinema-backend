package edu.czu.cinema.domain.movie;

import edu.czu.cinema.common.exception.GlobalExceptionHandler;
import edu.czu.cinema.common.util.ContextHolder;
import edu.czu.cinema.domain.movie.controller.MovieAdminController;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.service.MovieMetadataService;
import edu.czu.cinema.domain.movie.sync.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MovieMetadataControllerTest {
    final MovieSyncService sync = mock(MovieSyncService.class);
    final MovieMetadataService metadata = mock(MovieMetadataService.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MovieAdminController(sync,metadata))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    @AfterEach void clear() { ContextHolder.clear(); }

    @Test void anonymousAndNormalUsersCannotRepairOrEdit() throws Exception {
        mvc.perform(post("/api/admin/movie/repair")).andExpect(jsonPath("$.code").value(2001));
        ContextHolder.set(new ContextHolder.Principal(2L,"用户","USER"));
        mvc.perform(post("/api/admin/movie/repair")).andExpect(jsonPath("$.code").value(2002));
        mvc.perform(put("/api/admin/movie/12/metadata").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fields\":{\"runtimeMin\":\"100\"},\"expectedRevision\":0}"))
                .andExpect(jsonPath("$.code").value(2002));
        verifyNoInteractions(sync,metadata);
    }

    @Test void administratorCanStartRepairAndSubmitTypedPatch() throws Exception {
        ContextHolder.set(new ContextHolder.Principal(1L,"管理员","ADMIN"));
        when(sync.startRepair(null)).thenReturn(true);
        mvc.perform(post("/api/admin/movie/repair")).andExpect(jsonPath("$.data.started").value(true));
        Movie movie = new Movie(); movie.setId(12L); movie.setManualFieldKeys("runtimeMin");
        when(metadata.edit(eq(12L),any())).thenReturn(movie);
        mvc.perform(put("/api/admin/movie/12/metadata").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fields\":{\"runtimeMin\":\"100\"},\"unlockFields\":[],\"expectedRevision\":0}"))
                .andExpect(jsonPath("$.data.lockedFields[0]").value("runtimeMin"))
                .andExpect(jsonPath("$.data.manualFieldKeys").doesNotExist());
        verify(metadata).edit(eq(12L),argThat(r -> r.fields().get("runtimeMin").equals("100") && r.expectedRevision()==0));
    }
}
