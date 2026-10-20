package com.game.demo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 基于 HikariCP 的 JDBC 连接池单例。
 * 配置来自 classpath 下的 db.properties。
 */
public class MySQLUtil {

    private static final HikariDataSource DATA_SOURCE;

    static {
        Properties props = new Properties();
        try (InputStream in = MySQLUtil.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in == null) {
                throw new IllegalStateException("找不到 db.properties，请放到 src/main/resources 下");
            }
            props.load(in);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.getProperty("db.jdbcUrl"));
        cfg.setUsername(props.getProperty("db.username"));
        cfg.setPassword(props.getProperty("db.password"));
        cfg.setMaximumSize(Integer.parseInt(props.getProperty("db.maxPoolSize", "10")));
        cfg.setMinimumIdle(2);
        cfg.setPoolName("GameDbPool");
        cfg.setLeakDetectionThreshold(30_000);

        DATA_SOURCE = new HikariDataSource(cfg);
    }

    public static Connection getConnection() throws SQLException {
        return DATA_SOURCE.getConnection();
    }

    public static void close() {
        DATA_SOURCE.close();
    }
}
