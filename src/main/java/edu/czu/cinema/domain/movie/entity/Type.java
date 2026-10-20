package edu.czu.cinema.domain.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@TableName("type")
@Schema(description = "电影类型")
public class Type {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;

    @TableField(exist = false)
    private Integer count;
}
