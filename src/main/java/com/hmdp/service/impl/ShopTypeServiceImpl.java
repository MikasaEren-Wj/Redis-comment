package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //使用Redis缓存实现展示店铺类型列表
    @Override
    public Result queryList() {
        //1. 从Redis中查询店铺类型列表 使用Redis 自带的集合类型List
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOPTYPE_KEY, 0, -1);
        //2.判断集合是否为空
        if (shopTypeList != null && shopTypeList.size() > 0) {
            //3.不为空,转为List<ShopType>集合对象直接返回
            List<ShopType> shopTypes = shopTypeList.stream()
                    .map(item -> JSONUtil.toBean(item, ShopType.class))
                    .toList();
            return Result.ok(shopTypes);
        }
        //4.否则从数据库中查询,并按sort字段升序排序
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //5.若为空，直接返回
        if (shopTypes == null) {
            return Result.fail("店铺类型不存在!");
        }
        //6.不为空，将数据转化为JSON存入Redis中
        List<String> jsonList = shopTypes.stream().map(JSONUtil::toJsonStr).toList();
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOPTYPE_KEY, jsonList);
        return Result.ok(shopTypes);
    }
}
