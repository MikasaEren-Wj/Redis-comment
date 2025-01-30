package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ErenMikasa
 * Date 2024/10/23
 */
//使用分布式锁-redisson
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://192.168.168.1:6379")// ip:port
                .setPassword("wj515253");// 密码
        //创建RedissonClient对象
        return Redisson.create(config);
    }
}
