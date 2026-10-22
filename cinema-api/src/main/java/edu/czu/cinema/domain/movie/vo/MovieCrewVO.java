package edu.czu.cinema.domain.movie.vo;

import edu.czu.cinema.domain.movie.entity.Person;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "影片演职 (导演/编剧/演员) 全集")
public class MovieCrewVO {
    private Person person;
    /** 1 ACTOR / 2 DIRECTOR / 3 SCREENWRITER. */
    private Integer roleType;
    private String characterName;
    private String jobName;
    private Integer order;
}
