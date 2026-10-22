package edu.czu.cinema.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** Bind the complete pool configuration before opening any connections. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataSourceProperties.class)
public class DatabaseConfig {
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariConfig cinemaPoolProperties(DataSourceProperties properties) {
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(properties.determineUrl());
        pool.setUsername(properties.determineUsername());
        pool.setPassword(properties.determinePassword());
        pool.setDriverClassName(properties.determineDriverClassName());
        return pool;
    }

    @Bean(destroyMethod = "close")
    public DataSource dataSource(HikariConfig cinemaPoolProperties) {
        return new HikariDataSource(cinemaPoolProperties);
    }
}
