package edu.czu.cinema.domain.order.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.order.entity.Order;

public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 评价前锁定订单行, 防「同一单并发双评价」.
     *
     * <p>原 @Select 手写 SQL 已按 MP 统一改写为 LambdaQueryWrapper;
     * FOR UPDATE 是行锁语义不是过滤条件, 用 .last() 原样保留.
     * 方法签名不变, score 域调用方无感.
     */
    default Order lockForReview(Long id) {
        return selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getId, id)
                .last("FOR UPDATE"));
    }
}
