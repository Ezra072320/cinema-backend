package edu.czu.cinema.domain.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.admin.entity.Admin;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminMapper extends BaseMapper<Admin> {
}
