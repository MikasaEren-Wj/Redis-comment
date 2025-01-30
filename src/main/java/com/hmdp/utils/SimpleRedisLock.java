package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * ErenMikasa
 * Date 2024/10/22
 */
//使用Redis实现分布式锁 SETNX
public class SimpleRedisLock implements ILock {
    //具体业务的名字 用于拼接Redis的key
    private String name;
    private final StringRedisTemplate stringRedisTemplate;

    //锁的前缀名 用于拼接Redis的key
    private static final String KEY_PREFIX = "lock:";
    //线程标识的前缀
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //静态读取lua脚本(避免每次都要重新读取) 实现Redis分布式锁的释放 保证释放过程的原子性
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));//resources资源夹下脚本文件
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    //尝试获取锁 并设置锁的过期时间
    @Override
    public boolean tryLock(Long timeout) {
        //获取线程标识作为锁的value 同时作为释放锁时用于判断是不是自己的锁
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁 即Redis的SETNX操作
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//防止自动拆箱带来空指针异常
    }

    //释放锁 调用lua脚本 保证释放过程的原子性
    @Override
    public void unLock() {
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,//执行的脚本
                Collections.singletonList(KEY_PREFIX + name),//锁的标识以集合的方式传入
                ID_PREFIX + Thread.currentThread().getId());//当前线程的标识
    }

//    //释放锁 即Redis的DEL操作 注意：只能释放获取的锁
//    @Override
//    public void unLock() {
//        //获取线程的标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁的value
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断是否是自己获取的锁
//        if(threadId.equals(id)) {//若是则释放
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//        //否则不释放
//    }
}
