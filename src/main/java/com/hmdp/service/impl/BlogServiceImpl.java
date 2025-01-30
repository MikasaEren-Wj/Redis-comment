package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    //根据博客id查询博客
    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在!");
        }
        //2.查询得到发布笔记的用户姓名
        queryBlogUser(blog);
        //3.查询博客是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }


    //查询热门博客
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户信息 以及博客是否被点赞
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    //判断博客是否被当前用户点赞 用于博客点赞信息展示
    private void isBlogLiked(Blog blog) {
        //1.获取当前登录用户信息 使用UserDTO 隐藏敏感信息
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null){//目前没有用户登录
            //则直接返回
            return ;
        }
        Long userId = userDTO.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        //2.查询redis判断当前用户是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    //修改博客的点赞数量 使用redis中的sortedset集合存储当前博客点赞用户
    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //2.查询redis判断当前用户是否已经点赞 sortedset集合中的score
        String key = RedisConstants.BLOG_LIKED_KEY + id;//redis的key
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //2.1 若为空未点赞，则点赞
            //更新博客点赞数量 +1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {//点赞成功，则添加到redis 设置按照点赞时间先后排序
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            //2.2 若已点赞，则取消点赞
            //更新博客点赞数量 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {//取消成功，则从redis中删除点赞用户
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    //按时间展示博客点赞的前5位用户的头像
    @Override
    public Result queryBlogLikes(Integer id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //1.查询前5个点赞用户id 即zset集合的前5个元素 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null || top5.isEmpty()){//无人点赞
            return Result.ok(Collections.emptyList());//返回一个空集合
        }
        //2.获取这些用户的id
        List<Long> ids = top5.stream().map(Long::valueOf).toList();
        //因为SQL语句按in(id1,id2...)查询出来的结果并不是和zset集合顺序一致的
        //没办法，这是sql查询本身的毛病
        //所以我们需要用order by field来指定排序方式，这样才能和zset的集合中元素顺序一致
        //3.这里需要先将ids使用`,`拼接
        String idStr = StrUtil.join(",", ids);
        //4.拼接sql语句：select * from tb_user where id in (ids[0], ids[1] ...) order by field(id, ids[0], ids[1] ...)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("order by field(id," + idStr + ")")
                .list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))//将user转为userDTO
                .toList();
        return Result.ok(userDTOS);
    }

    //实现发布博客，同时推送给其粉丝
    @Override
    public Result saveBlog(Blog blog) {
        //1.获取当前登录用户
        Long followerId = UserHolder.getUser().getId();
        blog.setUserId(followerId);
        //2.保存博客到数据库
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("保存笔记失败!");
        }
        //3.查询登录用户所有的粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", followerId).list();
        //4.推送笔记给所有用户
        for (Follow follow : follows) {
            //4.1获取粉丝的id
            Long userId = follow.getUserId();
            //4.1 推送给粉丝 使用redis中的sortedset集合 实现滚动分页展示
            String key = RedisConstants.FEED_KEY + userId;
            Long blogId = blog.getId();
            //存储到redis 排序依据为时间戳 越大排序越前
            stringRedisTemplate.opsForZSet().add(key, blogId.toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    //实现滚动分页展示所关注的用户的博客
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获得当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.查询当前用户的收件箱(即redis缓存，由关注用户推送过来的)
        String key = RedisConstants.FEED_KEY + userId;
        ////按分数降序排序(即时间戳,最小值设为0) 页面最上面显示最新的博客，一页展示两条
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate
                .opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //4.解析查询redis得到的数据，得到blogId,minTime(时间戳，上页查询后的最新数据的时间戳),offset(偏移量，查询本页数据),
        List<Long> ids = new ArrayList<>(typedTuples.size());//存储博客id
        long minTime = 0;//初始为0
        int offsetCount=1;//计算偏移量
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //获取博客id
            ids.add(Long.valueOf(tuple.getValue()));
            //获取分数(时间戳)
            long time = tuple.getScore().longValue();
            if(time == minTime){//相同时间戳，则偏移量加一
                offsetCount++;
            }else{//不同时间戳，则重置偏移量
                minTime = time;
                offsetCount = 1;
            }
        }
        //5.根据id查询blog 为了保持redis中原本顺序 这里的查询需要拼接sql
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //5.1获得发送博客的用户信息
            queryBlogUser(blog);
            //5.2判断是否点赞
            isBlogLiked(blog);
        }
        //6.封装结果到自定义类 ScrollResult中并返回
        return Result.ok(new ScrollResult(blogs, minTime, offsetCount));
    }

    //根据博客查询用户信息 封装到blog中
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
