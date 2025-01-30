package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;//注入Redis操作模板

    //使用Redis保存验证码代替session存储验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //判断手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            //若不合法，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //手机号合法，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //验证码保存到redis 有效期为2分钟 定义一些常量(类RedisConstants中)来代替参数 更加专业
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码（仅模拟）
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //获得用户输入手机号和验证码
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //若不合法，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //2.从Redis中获取验证码校验
        String redisCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (redisCode == null || !redisCode.equals(code)) {
            //若验证码不正确，返回错误信息
            return Result.fail("验证码错误!");
        }
        //3.手机号和验证码均通过 根据手机号查询用户
        User user = this.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone));
        //4.判断用户是否存在
        if (user == null) {
            //创建用户
            user = createUserWithPhone(phone);
        }
        //5.保存用户信息到Redis
        //5.1 随机生成一个token作为登录令牌，即用户保存到Redis的key
        String token = UUID.randomUUID().toString(true);
        //5.2 将User转化为HashMap对象 方便以hash的方式存储到Redis
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO, new HashMap<>(), // 将userDTO中的属性值转化为map
                CopyOptions.create()
                        .setIgnoreNullValue(true)// 设置忽略值为null的字段
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));// 将map中字段的值转化为字符串
        //5.3 存储
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;//再添加一个常量组成redis的key
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //5.4 设置token有效期
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //6.注意最后返回token到前端 方便后续的登录验证
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        //设置电话和昵称
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);//保存到数据库
        return user;
    }
}
