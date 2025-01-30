package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;//封装解决缓存穿透 缓存击穿的工具类

    //更新店铺信息 缓存更新策略：先更新数据库，再删除缓存（主动更新为主，过期淘汰兜底）
    @Override
    @Transactional//加入事务 确保更新和删除的原子性
    public Result update(Shop shop) {
        //1.获得店铺的ID
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺ID为空!");
        }
        //2.根据店铺id更新店铺信息
        updateById(shop);
        //3.删除Redis缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    //加入Redis缓存，提升店铺查询的效率，并解决缓存穿透 缓存击穿问题
    @Override
    public Result queryById(Long id) {//店铺id
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = queryWithMutex(id);//互斥锁解决缓存击穿问题
//        Shop shop = queryWithLocalExpire(id);//逻辑过期解决缓存击穿问题

        //工具类封装方法实现缓存穿透 缓存击穿

//        Shop shop = cacheClient.queryWithPassThrough( RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
//                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        Shop shop = cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在!");
        }
        return Result.ok(shop);
    }



/*
    //创建一个线程池 用于逻辑过期
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期 解决缓存击穿
    private Shop queryWithLocalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;//key
        //1.从Redis中查询店铺
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isBlank(shopJson)) {
            //3.未命中，直接返回（因为是逻辑过期，只要存在热点店铺必然缓存命中）
            return null;
        }
        //4.若缓存命中，则将JSON反序列化为Shop对象 两步
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //获得对应在缓存中的过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //如果未过期，则直接返回对象
            return shop;
        }

        //6.如果已经过期，进行缓存重建
        //6.1 获得互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;//key
        boolean isLock = tryLock(lockKey);
        //6.2 判断互斥锁是否获取成功
        if (isLock) {
            //6.3 获取锁成功 开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //缓存重建 即重新设置逻辑过期时间
                    Thread.sleep(200);//模拟缓存重建消耗的时间
                    this.saveShopRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.4 获取锁失败,再次检测缓存是否过期（可能获得锁的进程实现了缓存重建），防止堆积的线程先后获得锁后都访问请求数据库，造成数据库压力
        shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            //未命中，直接返回
            return null;
        }
        //若缓存命中，则将JSON反序列化为Shop对象
        redisData = JSONUtil.toBean(shopJson, RedisData.class);
        shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        expireTime = redisData.getExpireTime();//获得对应在缓存中的过期时间
        if (expireTime.isAfter(LocalDateTime.now())) {
            //如果未过期，则直接返回对象
            return shop;
        }
        return shop;//否则此时返回的是过期数据
    }


    //逻辑过期中的缓存预热(也用于缓冲重建)：即将制定店铺设置为热点店铺，添加到缓存中，并设置逻辑过期时间，后续过期需要重建缓存
    public void saveShopRedis(Long id, Long expireSecond) {
        //1.从数据库中查询店铺数据 即将其设置为热点店铺 若过期需要缓存重建
        Shop shop = getById(id);
        //2.封装成RedisData对象
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(expireSecond);//过期时间
        RedisData redisData = new RedisData(expireTime, shop);
        //3.写入Redis缓存中
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    //互斥锁解决缓存击穿
    private Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;//key
        //1.从Redis中查询店铺
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {//存在且不为空字符串
            //直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.若此时存在缓存，但为空字符串，返回空
        if (shopJson != null) {
            return null;
        }

        //4.使用互斥锁，实现缓存重建，即允许一个请求查询数据库建立缓存
        //4.1尝试获得互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;//先构建一个key
        Shop shop = null;

        try {
            boolean isLock = tryLock(lockKey);//尝试获得互斥锁
            if (!isLock) {
                //4.2若获取互斥锁失败，休眠一段时间后递归继续获取
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 若获取互斥锁成功
            // 第二次检查，需要再查询一次Redis缓存，判断缓存是否重建,若已重建则直接访问缓存，防止堆积的线程先后获得锁后全部请求查询数据库
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {//4.5 缓存中的数据依旧存在
                //直接返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) {//判断空字符串，避免缓存穿透
                return null;
            }

            //4.6 否则缓存中不存在，即为null，则再从数据库中查询
            shop = getById(id);
            //5.判断数据库是否存在店铺,若不存在，就向Redis的缓存中存入一个空字符串,防止缓存穿透
            if (shop == null) {
                //对应键的值为空字符串 且设置较短的过期时间
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在店铺，则以字符串的形式存入缓存，并设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            //7.最后返回该店铺数据
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            unLock(lockKey);
        }
    }

    //抽取出缓存穿透的解决方法
    private Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;//key
        //1.从Redis中查询店铺
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {//存在且不为空字符串
            //直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //3.若此时存在缓存，但为空字符串，返回错误信息,防止缓存穿透
        if (shopJson != null) {
            return null;
        }

        //4.缓存中不存在，即为null，则从数据库中查询
        Shop shop = getById(id);
        //5.判断数据库是否存在店铺,若不存在，就向Redis的缓存中存入一个空字符串,防止缓存穿透
        if (shop == null) {
            //对应键的值为空字符串 且设置较短的过期时间
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在店铺，则以字符串的形式存入缓存，并设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.最后返回该店铺数据
        return shop;
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
*/

}
