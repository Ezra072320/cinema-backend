package edu.czu.cinema.domain.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.admin.entity.AdminOperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminOperationLogMapper extends BaseMapper<AdminOperationLog> {
}
