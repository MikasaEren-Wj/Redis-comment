package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * ErenMikasa
 * Date 2024/10/22
 */
//全局ID生成器 全局唯一id存储在Redis 1位符号位+31位时间戳+32位自增序列号
@Component
public class RedisWorker {
    //开始的时间戳 2022-01-01 00:00:00
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //自增序列号位数
    private static final long COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;
    public RedisWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //全局ID生成
    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);//计算秒数
        long timestamp = nowSecond - BEGIN_TIMESTAMP;//得到时间戳
        //2.生成序列号
        //2.1 获取当前日期，精确到天 利于在Redis中分层存放 便于计算某天某月某年的订单量等
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2 设置Redis自增长key
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":"+date);
        //3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}
