package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cglib.core.internal.Function;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ErenMikasa
 * Date 2024/10/22
 */
//封装工具类 解决缓存穿透 缓存击穿（逻辑过期方法）
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    //创建一个线程池 用于逻辑过期
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Description  将任意Java对象序列化为JSON，存储到Redis缓存中，并可以设置TTL过期时间
     * @param: key 缓存中的key
     * @param: value 要存储的java对象
     * @param: time  过期时间
     * @param: unit 过期时间单位
     * return void
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * Description   将任意Java对象序列化为JSON，存储到Redis缓存中，并可以设置逻辑过期时间
     * @param: key  缓存中的key
     * @param: value 要存储的java对象
     * @param: time  过期时间
     * @param: unit  过期时间单位
     * return void
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * Description  根据指定的Key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     * @param: keyPrefix 缓存中key的前缀
     * @param: id 要查询的id 可用于拼接缓存key
     * @param: type 要返回的java对象类型
     * @param: dbFallback 调用查询数据库的函数 采用函数式编程 传入一个参数返回一个对象
     * @param: time 过期时间
     * @param: unit 过期时间单位
     * return R 返回指定java类型的对象
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type,
            Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;//key
        //1.从Redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(json)) {//存在且不为空字符串
            //直接返回
            return JSONUtil.toBean(json, type);
        }
        //3.若此时存在缓存，但为空字符串，返回错误信息,防止缓存穿透
        if (json != null) {
            return null;
        }

        //4.缓存中不存在，即为null，则从数据库中查询
        R r = dbFallback.apply(id);
        //5.判断数据库是否存在,若不存在，就向Redis的缓存中存入一个空字符串,防止缓存穿透
        if (r == null) {
            //对应键的值为空字符串 且设置较短的过期时间
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，则以字符串的形式存入缓存，并设置过期时间
        this.setWithLogicalExpire(key, r, time, unit);
        //7.最后返回该数据
        return r;
    }
    /**
     * Description  根据指定的Key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
     * @param: keyPrefix 缓存中key的前缀
     * @param: id 要查询的id 可用于拼接缓存key
     * @param: type 要返回的java对象类型
     * @param: dbFallback 调用查询数据库的函数 采用函数式编程 传入一个参数返回一个对象
     * @param: time 过期时间
     * @param: unit 过期时间单位
     * return R 返回指定java类型的对象
     */
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type,
            Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;//key
        //1.从Redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isBlank(json)) {
            //3.未命中，直接返回（因为是逻辑过期，只要存在热点店铺必然缓存命中）
            return null;
        }
        //4.若缓存命中，则将JSON反序列化为Shop对象 两步
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //获得对应在缓存中的过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //如果未过期，则直接返回对象
            return r;
        }

        //6.如果已经过期，进行缓存重建
        //6.1 获得互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;//互斥锁key
        boolean isLock = tryLock(lockKey);
        //6.2 判断互斥锁是否获取成功
        if (isLock) {
            //6.3 获取锁成功 开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //缓存重建 即查询数据库+重新设置逻辑过期时间
                    R newR = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.4 获取锁失败,再次检测缓存是否过期（可能获得锁的进程实现了缓存重建），防止堆积的线程先后获得锁后都访问请求数据库，造成数据库压力
        json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            //未命中，直接返回
            return null;
        }
        //若缓存命中，则将JSON反序列化为Shop对象
        redisData = JSONUtil.toBean(json, RedisData.class);
        r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
//        expireTime = redisData.getExpireTime();//获得对应在缓存中的过期时间
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            //
//            return r;
//        }
        //如果未过期，则直接返回对象;否则此时返回的是过期数据
        return r;
    }


    /**
     * Description  根据指定的Key查询缓存，并反序列化为指定类型，需要利用互斥锁解决缓存击穿问题
     * @param: keyPrefix 缓存中key的前缀
     * @param: id 要查询的id 可用于拼接缓存key
     * @param: type 要返回的java对象类型
     * @param: dbFallback 调用查询数据库的函数 采用函数式编程 传入一个参数返回一个对象
     * @param: time 过期时间
     * @param: unit 过期时间单位
     * return R 返回指定java类型的对象
     */
    public <R,ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type,
            Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;//key
        //1.从Redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(json)) {//存在且不为空字符串
            //直接返回
            return JSONUtil.toBean(json, type);
        }
        //3.若此时存在缓存，但为空字符串，返回空
        if (json != null) {
            return null;
        }

        //4.使用互斥锁，实现缓存重建，即允许一个请求查询数据库建立缓存
        //4.1尝试获得互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;//先构建一个key
        R r= null;

        try {
            boolean isLock = tryLock(lockKey);//尝试获得互斥锁
            if (!isLock) {
                //4.2若获取互斥锁失败，休眠一段时间后递归继续获取
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            //4.4 若获取互斥锁成功
            // 第二次检查，需要再查询一次Redis缓存，判断缓存是否重建,若已重建则直接访问缓存，防止堆积的线程先后获得锁后全部请求查询数据库
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {//4.5 缓存中的数据依旧存在
                //直接返回
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {//判断空字符串，避免缓存穿透
                return null;
            }

            //4.6 否则缓存中不存在，即为null，则再从数据库中查询
            r = dbFallback.apply(id);
            //5.判断数据库是否存在店铺,若不存在，就向Redis的缓存中存入一个空字符串,防止缓存穿透
            if (r == null) {
                //对应键的值为空字符串 且设置较短的过期时间
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在店铺，则以字符串的形式存入缓存，并设置过期时间
            this.setWithLogicalExpire(key, r, time, unit);
            //7.最后返回该店铺数据
            return r;
        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            unLock(lockKey);
        }
    }

    //获取互斥锁，解决缓存击穿 原理为Redis的SETNX操作:若给定的 key 已经存在，则 SETNX 不做任何动作。
    private boolean tryLock(String key) {
        //设置一个值看是否成功  SETNX对应java中的setIfAbsent
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, RedisConstants.LOCK_SHOP_value, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//使用工具类判断，防止出现空指针异常
    }

    //释放互斥锁，解决缓存击穿 原理为Redis的DEL操作
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
