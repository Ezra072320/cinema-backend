package edu.czu.cinema.ai.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.ai.entity.AiAction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiActionMapper extends BaseMapper<AiAction> {
    /** 行锁取服务单：确认/撤销要串行化并重查，FOR UPDATE 是唯一允许保留的 SQL 片段（其余手写 SQL 清零）。 */
    default AiAction lockById(String id) {
        return selectOne(new LambdaQueryWrapper<AiAction>().eq(AiAction::getId, id).last("FOR UPDATE"));
    }
}