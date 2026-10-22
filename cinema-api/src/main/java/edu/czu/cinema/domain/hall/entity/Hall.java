package edu.czu.cinema.domain.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@TableName("hall")
@Schema(description = "影厅")
public class Hall {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    /** 1 标准 2D / 2 标准 3D / 3 IMAX 2D / 4 IMAX 3D */
    private Integer type;

    /**
     * 座位数. **由座位图算出来, 不手填** —— 等于当前生效版本里 {@code status=1} 的座位数,
     * 由 {@code SeatVersionService.applyDraft} 在发布座位图时写入.
     *
     * <p>建厅时是 0(还没画座位图), 画完并发布之后才有值. 过道格({@code status=0})不算座位.
     *
     * <p>曾经还有个 {@code total_seats} 列存同样的东西, 两个字段一个含义迟早对不上, 已删 ——
     * 见 {@code 2026_10_16__hall_capacity_from_seatmap.sql}.
     *
     * <p>门户影厅列表显示的就是这个字段, 所以它必须反映"真的画了多少座位",
     * 而不是管理员当初填了多少.
     */
    private Integer capacity;

    /**
     * 当前生效的座位图版本 id, 指向 {@code hall_seat_version.id}.
     * 新建厅时为 NULL, Phase 3 的 SeatVersionService 会自动建 v1.
     * 老数据由 Phase 2 的回填脚本补齐.
     */
    @TableField("current_version_id")
    private Long currentVersionId;

    @TableField(exist = false)
    private List<Seat> seats;

    /**
     * 当前生效版本里的情侣对. 只在影厅详情接口下发(列表接口不带, 免得白拉一堆数据).
     *
     * <p>前端据此把一对的两个格子合并成一个视觉单元 —— 跟选座页/编辑器一致.
     * 不返回的话前端只能靠"相邻两个 type=2"去猜, 三个连续的情侣座就会猜错.
     */
    @TableField(exist = false)
    private List<SeatCoupleGroup> coupleGroups;
}
