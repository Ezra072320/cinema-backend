package edu.czu.cinema.domain.movie;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import edu.czu.cinema.domain.movie.mapper.MovieMapper;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 双排行榜口径(H2): - topSales 只算真实已支付票款 —— ticket.status=0(仍挂订单) 且 order.status IN(2,6),
 * 剔除待支付(1)/已退款(3)/作废票(2)/未售票(1); - topBoxOffice 按公开累计票房(total_box)降序, 无票房数据的不上榜.
 */
class MovieTopSqlTest {

    @Test
    void topSalesCountsOnlyPaidNonVoidedTickets() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:movietop;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                """
CREATE TABLE movie(id BIGINT PRIMARY KEY,title VARCHAR(255),poster_url VARCHAR(512),
total_box DECIMAL(14,2),score DECIMAL(3,1),status INT,create_time TIMESTAMP)
""");
        jdbc.execute(
                "CREATE TABLE schedule(id BIGINT PRIMARY KEY,movie_id BIGINT,hall_id"
                    + " BIGINT,start_time TIMESTAMP,end_time TIMESTAMP,price DECIMAL(8,2),status"
                    + " INT,batch_id VARCHAR(36))");
        jdbc.execute(
                "CREATE TABLE ticket(id BIGINT PRIMARY KEY,schedule_id BIGINT,seat_id"
                    + " BIGINT,order_id BIGINT,status INT,price DECIMAL(8,2))");
        jdbc.execute(
                "CREATE TABLE `order`(id BIGINT PRIMARY KEY,number VARCHAR(64),user_id"
                    + " BIGINT,schedule_id BIGINT,total_amount DECIMAL(10,2),status INT,create_time"
                    + " TIMESTAMP)");

        // 3 部片: 公开票房 1亿/5000万/无
        jdbc.execute(
                "INSERT INTO movie"
                    + " VALUES(1,'甲','p1',100000000,9,1,NOW()),(2,'乙','p2',50000000,8,1,NOW()),(3,'丙','p3',0,7,1,NOW())");
        jdbc.execute(
                "INSERT INTO schedule"
                    + " VALUES(11,1,1,NOW(),NOW(),50,1,NULL),(12,2,1,NOW(),NOW(),50,1,NULL)");

        // 影片1: 已支付(2)60 + 已完成(6)80 = 140/2 张; 待支付(1)50、已退款(3)50、作废票99、未售票70 全剔除
        jdbc.execute(
                "INSERT INTO `order`"
                    + " VALUES(101,'A',1,11,60,2,NOW()),(102,'B',1,11,80,6,NOW()),(103,'C',1,11,50,1,NOW()),(104,'D',1,11,50,3,NOW())");
        jdbc.execute(
                "INSERT INTO ticket"
                    + " VALUES(1,11,1,101,0,60),(2,11,2,102,0,80),(3,11,3,103,0,50),(4,11,4,104,0,50),(5,11,5,-1,2,99),(6,11,6,-1,1,70)");
        // 影片2: 已支付(2)200 = 200/1 张
        jdbc.execute("INSERT INTO `order` VALUES(105,'E',1,12,200,2,NOW())");
        jdbc.execute("INSERT INTO ticket VALUES(7,12,1,105,0,200)");

        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(MovieMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/MovieMapper.xml"));
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        factory.setPlugins(pagination);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MovieMapper.class);

        // 销售额榜: 乙 200 > 甲 140; 丙无售票不上榜
        jdbc.execute("CREATE TABLE movie_snapshot AS SELECT * FROM movie");
        var sales = new edu.czu.cinema.query.TradeSalesQuery(jdbc).sales();
        assertThat(sales).hasSize(2);
        assertThat(sales.get(0).getMovieId()).isEqualTo(2L);
        assertThat(sales.get(0).getBoxOffice()).isEqualByComparingTo("200");
        assertThat(sales.get(0).getSoldCount()).isEqualTo(1L);
        assertThat(sales.get(1).getMovieId()).isEqualTo(1L);
        assertThat(sales.get(1).getBoxOffice()).isEqualByComparingTo("140");
        assertThat(sales.get(1).getSoldCount()).isEqualTo(2L);

        // 公开票房榜: 甲 1亿 > 乙 5000万; 丙 total_box=0 不上榜
        var box = mapper.topBoxOffice(10);
        assertThat(box).hasSize(2);
        assertThat(box.get(0).getMovieId()).isEqualTo(1L);
        assertThat(box.get(0).getTotalBox()).isEqualByComparingTo("100000000");
        assertThat(box.get(1).getMovieId()).isEqualTo(2L);
    }
}
