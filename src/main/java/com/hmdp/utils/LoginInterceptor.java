package com.hmdp.utils;

import ch.qos.logback.core.util.TimeUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ErenMikasa
 * Date 2024/10/20
 */
//拦截器 只拦截需要登录请求的路径
public class LoginInterceptor implements HandlerInterceptor {

    //实现登录请求拦截
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //只需从ThreadLocal中查询是否有用户 即可
        if(UserHolder.getUser()==null){
            response.setStatus(401);//未登录，需要拦截，设置状态码
            return false;//拦截
        }
        //已登录，放行
        return true;
    }

}
