package edu.czu.cinema.domain.movie.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 排行榜条目 —— 两个榜单共用一个 VO, 数值口径不同:
 * <ul>
 *   <li>影院销售额榜({@code /movie/topSales}): {@code boxOffice}=真实已支付票款
 *       (仅 ticket.status=0 且 order.status IN(2,6) 的票), {@code soldCount}=已售张数;</li>
 *   <li>公开票房榜({@code /movie/topBoxOffice}): {@code totalBox}=外部来源的全国累计票房
 *       (movie.total_box, 人工整理的公开数据), boxOffice/soldCount 恒为 0.</li>
 * </ul>
 * 两个量级相差 3~5 个数量级, 前端展示与进度条要分榜计算, 别混用.
 */
@Data
@Schema(description = "排行榜条目(公开票房 / 影院销售额)")
public class MovieSoldVO {
    private Long movieId;
    private String title;
    private String posterUrl;
    /** 影院销售额榜: 本影院真实已支付票款(ticket.price 求和, 已剔除待支付/退款/作废). */
    private BigDecimal boxOffice;
    private Long soldCount;
    /** 公开票房榜: 全国累计票房(外部公开数据, 元). */
    private BigDecimal totalBox;
}
