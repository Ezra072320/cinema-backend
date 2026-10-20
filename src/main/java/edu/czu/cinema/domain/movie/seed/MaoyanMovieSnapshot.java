package edu.czu.cinema.domain.movie.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * 猫眼电影详情页快照. record + @JsonProperty 匹配猫眼 snake/camel 字段.
 *
 * <p>字段全部 optional, 没有的字段允许为 null.
 * 这是管理员手动 / 离线导入的 JSON 数据格式;
 * 不依赖任何运行时网络, 也不会真正访问 maoyan.com.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaoyanMovieSnapshot(
        @JsonProperty("id")       Integer id,
        @JsonProperty("nm")       String nm,           // 中文名
        @JsonProperty("enm")      String enm,          // 英文名
        @JsonProperty("cat")      String cat,          // 类型 csv "剧情,动作"
        @JsonProperty("dir")      String dir,          // 导演 csv
        @JsonProperty("star")     String star,         // 主演 csv "李四 饰 阿明,王五 饰 阿华"
        @JsonProperty("story")    String story,        // 简介
        @JsonProperty("intro")    String intro,        // 备用简介
        @JsonProperty("sc")       BigDecimal sc,       // 评分
        @JsonProperty("snum")     Integer snum,        // 评分数
        @JsonProperty("wishCnt")  Integer wishCnt,     // 想看人数
        @JsonProperty("dur")      Integer dur,         // 时长(分钟)
        @JsonProperty("img")      String img,          // 海报
        @JsonProperty("bgImg")    String bgImg,        // 背景图
        @JsonProperty("photos")   List<String> photos, // 剧照
        @JsonProperty("videoUrl") String videoUrl,     // 预告片
        @JsonProperty("rt")       String rt,           // 上映日期 yyyy-MM-dd 或时间戳
        @JsonProperty("src")      String src,          // 产地
        @JsonProperty("fmt")      String fmt,          // 制式
        @JsonProperty("filmTags") List<String> filmTags, // 标签
        @JsonProperty("boxInfo")  BoxInfo boxInfo,     // 票房
        @JsonProperty("aka")      List<String> aka,    // 又名
        /** 0=经典 1=热映 2=即将上映. 显式给出时优先于 deriveStatus() 的推导. */
        @JsonProperty("status")   Integer status,
        /** 数据来源标记, 写入 movie.source. 缺省 MAOYAN. */
        @JsonProperty("source")   String source,
        /** 主语言. 缺省时从 cat 兜底. */
        @JsonProperty("language") String language
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BoxInfo(
            @JsonProperty("totalBox")     BigDecimal totalBox,
            @JsonProperty("firstDayBox")  BigDecimal firstDayBox,
            @JsonProperty("showDays")     Integer showDays
    ) {}
}
