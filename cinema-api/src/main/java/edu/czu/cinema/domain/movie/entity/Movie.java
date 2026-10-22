package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 电影主表.
 *
 * <p>2026-10-16 翻新: 新增 21 列匹配猫眼电影详情页公开字段.
 * 2026-10-17: 删除冗余老列 pic/time（分别并入 poster_url/runtime_min）。
 * 时长约定: {@code runtimeMin == 0} 表示「待映影片尚未公布时长」，是合法状态而非缺失资料。
 */
@Data
@TableName("movie")
@Schema(description = "电影")
public class Movie implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /* ===== 原有列 ===== */
    private String title;
    private String summary;
    private BigDecimal score;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private LocalDate releaseDate;

    private String area;
    /** 数据库旧 status 列保留为“大陆市场状态”，不再决定本店热映。 */
    @TableField("status")
    private Integer marketStatus;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /* ===== 2026-10-16 翻新列 ===== */
    private String originalTitle;
    private String language;
    /** 时长(分钟)；0 = 待映影片尚未公布时长，是合法状态而非缺失资料。 */
    private Integer runtimeMin;
    private String posterUrl;
    private String backdropUrl;
    private String trailerUrl;
    private String cat;
    private String dir;
    /** 当前资料来源：MANUAL / MAOYAN / DOUBAN / TMDB。外部 ID 分开存储。 */
    private String source;
    private Integer maoyanId;
    private Integer tmdbId;
    private String scoreSource;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime lastSyncedAt;
    /** 人工确认的字段键，逗号分隔；对外使用 lockedFields。 */
    @JsonIgnore
    @TableField("manual_fields")
    private String manualFieldKeys;
    private Integer manualRevision;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime lastRepairAt;

    public List<String> getLockedFields() {
        return manualFieldKeys == null || manualFieldKeys.isBlank() ? List.of()
                : java.util.Arrays.stream(manualFieldKeys.split(",")).filter(s -> !s.isBlank()).distinct().toList();
    }
    private Integer showDays;
    private BigDecimal firstDayBox;
    private BigDecimal totalBox;
    private String fmt;
    private Integer wishCount;
    private Integer scoreCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    /* ===== transient 字段 (不入库) ===== */
    @TableField(exist = false)
    private transient String actorNames;

    @TableField(exist = false)
    private transient String directorNames;

    @TableField(exist = false)
    private Boolean hasSchedules = false;
    @TableField(exist = false)
    private Integer scheduleCount = 0;
    @TableField(exist = false)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime nextShowTime;

    /** 兼容前端 status=1 的约定，但本店状态必须实时来自排片。 */
    public Integer getStatus() { return Boolean.TRUE.equals(hasSchedules) ? 1 : 0; }

    /** 只报告基础资料缺项；未开分、没有预告片都不是数据错误。 */
    public List<String> getMissingFields() {
        List<String> missing = new ArrayList<>();
        if (summary == null || summary.isBlank()) missing.add("简介");
        // 时长约定: 待映(上映日>今天)且 runtimeMin=0 是「尚未公布」的合法状态，不算缺项；
        // 已上映影片的 0 时长才是资料待补。
        if ((runtimeMin == null || runtimeMin <= 0) && !isUpcoming()) missing.add("时长");
        if (releaseDate == null) missing.add("上映日");
        if (cat == null || cat.isBlank()) missing.add("类型");
        if (dir == null || dir.isBlank()) missing.add("导演");
        if (area == null || area.isBlank()) missing.add("地区");
        if (language == null || language.isBlank()) missing.add("语言");
        if (posterUrl == null || posterUrl.isBlank()) missing.add("海报");
        return missing;
    }

    /** 大陆待映：上映日在今天之后。 */
    public boolean isUpcoming() {
        return releaseDate != null && releaseDate.isAfter(LocalDate.now());
    }
}
