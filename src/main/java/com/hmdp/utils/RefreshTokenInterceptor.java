package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ErenMikasa
 * Date 2024/10/20
 */
//token刷新拦截器 拦截一切路径
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;//这里只能使用构造函数的方式 因为这个类不由spring容器管理

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //实现登录请求拦截
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token 保存在请求头中的authorization中
        String token = request.getHeader("authorization");
        //2.判断token是否存在
        if (StrUtil.isBlank(token)) {
            return true;//不用拦截 直接放行 放给登录拦截器
        }
        //3.根据key从Redis中获取用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //4.判断用户是否存在
        if (userMap.isEmpty()) {
            return true;//不用拦截 直接放行 放给登录拦截器
        }
        //5.将查询到的userMap转化为userDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //6.保存用户信息到ThreadLocal 前端的每个请求保存到一个独立的线程，即一个独立的副本，互相之间不影响
        UserHolder.saveUser(userDTO);
        //7.刷新token有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        log.debug("刷新token成功");
        //放行
        return true;
    }

    //返回页面时线程中存储的用户信息 防止内存泄露
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
