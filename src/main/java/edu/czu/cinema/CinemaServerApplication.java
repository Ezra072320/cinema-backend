package edu.czu.cinema;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * 猫头鹰影院 - 应用启动入口.
 * <p>
 * 保留原项目"影院管理系统"的核心业务领域划分，引入 Spring Boot 3.5 + JDK 21
 * 与更现代的工程实践：Spring Security 自定义 JWT 过滤器、Knife4j 文档、
 * MyBatis-Plus 3.5 + Hutool 工具链。
 * </p>
 * <p>
 * JVM 默认时区固定为 Asia/Shanghai：散场判定、订单超时、营收分桶此前分别用
 * JVM 时钟 / Asia/Shanghai / SQL NOW()，JVM 时区不一致会产生错位。启动入口
 * 统一钉死后，LocalDateTime.now() 与 DB 会话时区一致。
 * </p>
 */
@EnableScheduling
@EnableCaching
@MapperScan("edu.czu.cinema.**.mapper")
@SpringBootApplication
public class CinemaServerApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(CinemaServerApplication.class, args);
    }
}
