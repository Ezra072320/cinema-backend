package edu.czu.cinema.domain.hall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.hall.entity.SeatTemplate;

import java.util.List;

public interface SeatTemplateMapper extends BaseMapper<SeatTemplate> {

    /**
     * 列出所有模板, 顺便带出每个模板的单元数. 管理员列表页直接用,
     * 避免列表后再 N+1 查 unit count.
     */
    List<SeatTemplate> selectListWithCount();
}
