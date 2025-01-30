package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    //实现当前用户关注与取消关注某个用户id 同时将当前用户的关注存入redis中 方便后续实现展示共同关注
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获得当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOWS_KEY + userId;
        //2.判断当前用户是否关注id用户
        if(BooleanUtil.isTrue(isFollow)){
            //如果关注当前用户，则添加到数据库中
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);//保存
            if(isSuccess){//保存成功 同时加入redis的set集合中 因为set可以实现求交集功能
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{//否则取消关注，删除数据库对应数据 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            //同时删除对应redis中的数据
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    //查看当前用户是否关注某个用户id
    @Override
    public Result isFollow(Long followUserId) {
        //1.获得当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.查询数据库，是否关注用户
        int count = (int) this.count(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId));
        return Result.ok(count>0);
    }

    //查看当前用户与其关注的某个用户的共同关注
    @Override
    public Result followCommons(Long id) {
        //1.获得当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.设置当前登录用户与关注用户的key
        String key1 = RedisConstants.FOLLOWS_KEY + userId;
        String key2 = RedisConstants.FOLLOWS_KEY + id;
        //3.查询redis求交集 即共同关注
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect == null || intersect.isEmpty()){//若没有共同关注则返回一个空集合
            return Result.ok(Collections.emptyList());
        }
        //4.解析获得共同关注的用户ids
        List<Long> ids = intersect.stream()
                .map(Long::valueOf)
                .toList();
        //5.获得对应用户的信息
        List<UserDTO> users = userService.listByIds(ids)//根据id获得用户集合
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .toList();

        return Result.ok(users);
    }
}
