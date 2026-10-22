package edu.czu.cinema.domain.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("seat")
@Schema(description = "座位")
public class Seat {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(value = "hall_id")
    private Long hallId;

    /**
     * 所属座位图版本 id, 指向 {@code hall_seat_version.id}.
     * 每次管理员改座位图并发布, 该 hall 的 seat 行会复制一份到新版本.
     * 旧版本的 seat 行不删, 是「冻结快照」的一部分.
     */
    @TableField("version_id")
    private Long versionId;

    /** 行 */
    private Integer y;
    /** 列 */
    private Integer x;
    /** 0 不可用 / 1 正常 */
    private Integer status;
    /** 1 标准 2 情侣 3 VIP */
    private Integer type;
}
