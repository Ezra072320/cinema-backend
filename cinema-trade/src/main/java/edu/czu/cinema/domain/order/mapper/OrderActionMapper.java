package edu.czu.cinema.domain.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.order.entity.OrderAction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderActionMapper extends BaseMapper<OrderAction> {
}
