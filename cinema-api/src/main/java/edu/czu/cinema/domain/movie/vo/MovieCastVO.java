package edu.czu.cinema.domain.movie.vo;

import edu.czu.cinema.domain.movie.entity.Person;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "影片主演 + 角色名 (Cast)")
public class MovieCastVO {
    private Person person;
    private String character;
    private Integer order;
}
