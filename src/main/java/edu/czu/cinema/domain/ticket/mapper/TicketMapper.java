package edu.czu.cinema.domain.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.czu.cinema.domain.ticket.entity.Ticket;

/**
 * 票表 Mapper.
 *
 * <p>原先这里有一堆手写 SQL(listBySchedule / lockTickets / voidBySchedule / count* ...),
 * 对应 TicketMapper.xml. 2026-10-20 起全部改为 MyBatis-Plus 条件构造器, 语句落在
 * {@code TicketServiceImpl} 里:
 * <ul>
 *   <li>单表 CRUD / 条件更新用 LambdaQueryWrapper / LambdaUpdateWrapper 表达即可,
 *       手写 XML 只会和 MP 风格分裂、每加一个条件就要改两处;</li>
 *   <li>lockTickets 的「条件 UPDATE + 影响行数 == 请求数」CAS 语义由
 *       {@code lambdaUpdate().in(id).eq(status,1).set(...)} 原样保留.</li>
 * </ul>
 * XML 已随迁移删除; 本接口只保留 BaseMapper 能力.
 */
public interface TicketMapper extends BaseMapper<Ticket> {
}
