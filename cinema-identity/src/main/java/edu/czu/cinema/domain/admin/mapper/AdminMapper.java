package edu.czu.cinema.domain.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.admin.entity.Admin;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface AdminMapper extends BaseMapper<Admin> {
    /** Serialize removals/freezes so concurrent administrators cannot remove the last enabled account. */
    @Select("SELECT id FROM admin ORDER BY id FOR UPDATE")
    List<Long> lockAccounts();
}
