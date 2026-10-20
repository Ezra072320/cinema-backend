package edu.czu.cinema.domain.hall.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.hall.entity.SeatTemplateUnit;

import java.util.List;

public interface SeatTemplateUnitMapper extends BaseMapper<SeatTemplateUnit> {

    default List<SeatTemplateUnit> listByTemplate(Long templateId) {
        return selectList(new LambdaQueryWrapper<SeatTemplateUnit>()
                .eq(SeatTemplateUnit::getTemplateId, templateId)
                .orderByAsc(SeatTemplateUnit::getY)
                .orderByAsc(SeatTemplateUnit::getX));
    }

    default int deleteByTemplate(Long templateId) {
        return delete(new LambdaQueryWrapper<SeatTemplateUnit>()
                .eq(SeatTemplateUnit::getTemplateId, templateId));
    }

    /**
     * 批量插入。保留手写 SQL 的唯一理由：MP BaseMapper 无批量 insert，
     * 逐条 insert 会产生 N 次往返；Service 层同事务内先删后插，无重复键问题。
     */
    int batchInsert(List<SeatTemplateUnit> units);
}
