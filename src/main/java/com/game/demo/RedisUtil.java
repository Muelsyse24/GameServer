package com.game.demo;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.InputStream;
import java.util.Properties;

/**
 * 基于 JedisPool 的 Redis 连接池单例。
 * 用法：try (Jedis jedis = RedisUtil.getJedis()) { ... }
 */
public class RedisUtil {

    private static final JedisPool POOL;

    static {
        Properties props = new Properties();
        try (InputStream in = RedisUtil.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in == null) {
                throw new IllegalStateException("找不到 db.properties");
            }
            props.load(in);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }

        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(Integer.parseInt(props.getProperty("redis.maxTotal", "16")));
        cfg.setMaxIdle(Integer.parseInt(props.getProperty("redis.maxIdle", "8")));
        cfg.setTestOnBorrow(true);

        String host = props.getProperty("redis.host", "127.0.0.1");
        int port = Integer.parseInt(props.getProperty("redis.port", "6379"));
        POOL = new JedisPool(cfg, host, port);
    }

    public static Jedis getJedis() {
        return POOL.getResource();
    }

    public static void close() {
        POOL.close();
    }
}
