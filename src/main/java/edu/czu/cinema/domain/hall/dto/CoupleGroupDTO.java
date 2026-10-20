package edu.czu.cinema.domain.hall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 一对情侣座. 前端在座位图编辑器里把同排相邻的两格配成一对.
 *
 * <p><b>为什么用坐标而不是 seatId 引用</b>: 编辑器的操作顺序是「画格子 → 配情侣对
 * → 保存」. 如果这次保存里包含了**新增**的座位, 前端手里根本没有它的 id
 * (id 要等服务端 insert 完才知道). 用坐标表达, 服务端先把座位 upsert 完,
 * 再按坐标反查 seat id 写绑定关系, 无论新增还是已有座位都能一次保存到位.
 *
 * <p>服务端会校验: 两个坐标上都有座位、同一排、x 相邻、且都可售(status=1).
 * 校验不过整批拒绝, 不会写半对进库.
 */
@Data
public class CoupleGroupDTO {

    @NotNull @Min(1) @Max(50)
    private Integer y1;

    @NotNull @Min(1) @Max(50)
    private Integer x1;

    @NotNull @Min(1) @Max(50)
    private Integer y2;

    @NotNull @Min(1) @Max(50)
    private Integer x2;

    @Size(max = 16)
    private String label;
}
