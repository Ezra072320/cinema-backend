package edu.czu.cinema.domain.movie.service;

import edu.czu.cinema.common.api.PageResult;
import edu.czu.cinema.domain.movie.entity.Movie;
import edu.czu.cinema.domain.movie.vo.*;

import java.util.List;

/**
 * 影片服务 —— <b>只读</b>.
 *
 * <p>资料写入由更新、补缺任务和 MovieMetadataService 的管理员核实补录处理。
 */
public interface MovieService {

    default PageResult<Movie> page(int page, int size, String title, Integer status) {
        return page(page, size, title, status, null);
    }
    default PageResult<Movie> page(int page, int size, String title, Integer marketStatus, Boolean showing) {
        return page(page,size,title,marketStatus,showing,null);
    }
    PageResult<Movie> page(int page, int size, String title, Integer marketStatus, Boolean showing, Boolean incomplete);

    List<Movie> hotMovies();

    /** 影院销售额榜: 真实已支付票款 Top10. */
    List<MovieSoldVO> topSales();

    /** 公开票房榜: 外部来源全国累计票房 Top10. */
    List<MovieSoldVO> topBoxOffice();

    MovieInfoVO info(Long movieId);

    /** 描述接口：供 AI 工具调用. */
    String describeBrief(String keyword);

    /**
     * 想看开关（toggle 行为）.
     * <b>返回值是切换后的状态</b>：true=现在想看（刚加入），false=已取消想看 ——
     * 不是"操作是否成功"，失败走异常。前端 WantButton 直接拿它驱动提示与按钮态。
     */
    boolean handleWant(Long userId, Long movieId);

    /** 用户想看列表. */
    List<Long> userWantList(Long userId);

    /** 用户是否已点过想看. */
    boolean isWant(Long movieId, Long userId);

    /** 评分更新. */
    boolean updateScoreByOrder(Long orderId, Integer score);

    /** 类型列表. */
    PageResult<edu.czu.cinema.domain.movie.entity.Type> listTypes(String name, int page, int size);

    /* ===== 2026-10-16 翻新: 卫星资源读取 ===== */

    /** 影片全部图片 (海报/背景图/剧照). */
    List<MovieImageVO> images(Long movieId);

    /** 影片全部视频 (预告片等). */
    List<MovieVideoVO> videos(Long movieId);

    /** 影片演职 (可按 roleType 过滤: 1 ACTOR / 2 DIRECTOR / 3 SCREENWRITER). */
    List<MovieCrewVO> crew(Long movieId, Integer roleType);
}
