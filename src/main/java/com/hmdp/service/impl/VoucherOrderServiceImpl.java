package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisWorker redisWorker;//全局ID生成器

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    //静态读取lua脚本(避免每次都要重新读取) 实现优惠券秒杀优化
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));//resources资源夹下脚本文件
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建阻塞队列 存放要写入数据库的订单  一旦类加载,就开启异步线程处理阻塞队列里面的订单任务
//    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);//阻塞队列大小

    //消息队列的名称
    private final String queueName = "stream.orders";

    //创建一个线程池 开启异步线程处理消息队列
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //为使类一旦加载完成就处理消息队列 使用一个注解
    @PostConstruct
    private void init() {//加载类后开始执行线程 处理阻塞队列
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //定义一个内部类 即标识异步线程 处理阻塞队列 完成订单写入数据库
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    /*
                    //1.获取阻塞队列中的订单
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.开始处理 存入数据库
                    handleVoucherOrder(voucherOrder);//封装成一个方法
                     */
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),//消费者组的名称和key
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),//消息队列为空时阻塞等待2秒
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));//读取第一条未被处理的消息
                    //2.判断消息是否获取成功
                    if (records == null || records.isEmpty()) {
                        continue;//进入下一轮循环
                    }
                    //3.获得消息后，将其转化为订单对象
                    MapRecord<String, Object, Object> record = records.get(0);//获取第一个数据
                    Map<Object, Object> values = record.getValue();//获得值即订单信息
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.处理订单 即下单存入数据库
                    handleVoucherOrder(voucherOrder);
                    //5.手动ACK确认消息，SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    //封装成一个方法处理异常订单 即pending-list中的消息
                    handlePendingList();
                }
            }
        }

        //处理了消息队列中的异常订单
        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),//消费者组的名称和key
                            StreamReadOptions.empty().count(1),//这里为空时不再阻塞等待
                            StreamOffset.create(queueName, ReadOffset.from("0")));//读取第一条未被处理的消息
                    //2.判断是否还有未处理的消息
                    if (records == null || records.isEmpty()) {
                        break;//若没有，直接退出
                    }
                    //3.获取消息后，将其转化为订单对象
                    MapRecord<String, Object, Object> record = records.get(0);//获取第一个数据
                    Map<Object, Object> values = record.getValue();//获得值即订单信息
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.处理订单 即下单存入数据库
                    handleVoucherOrder(voucherOrder);
                    //5.手动ACK确认消息，SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    // 这里不用调自己，直接就进入下一次循环，再从pendingList中取，这里只需要休眠一下，防止获取消息太频繁
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        log.error("线程休眠异常", ex);
                    }

                }
            }
        }
    }


    //处理任务
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.从订单中获取用户id
        Long userId = voucherOrder.getUserId();

        //2.使用Redis实现分布式锁 限制一人一单
        //2.1 用redisson的分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //2.2 尝试获取锁 设置锁的过期时间
        boolean isLock = lock.tryLock();//默认参数为空
        //2.3 判断是否获取锁成功
        if (!isLock) {//获取锁失败
            log.info("请勿重复下单~");
            return;
        }
        try {
            //3.因为这里开启的是异步线程处理任务 所以事务对象需要提前获取好 写入订单到数据库
            proxy.creatVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();//释放锁
        }
    }

    private IVoucherOrderService proxy;//事务的代理对象需要额外提前获取

    //实现优惠券秒杀 使用lua脚本实现下单资格判断 开启异步线程完成写入数据库 提高吞吐量
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.获取用户ID
        Long userId = UserHolder.getUser().getId();
        //2.生成订单id
        long orderId = redisWorker.nextId("order");
        //3.执行lua脚本 判断是否有下单资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),//这里传入一个空集合 因为lua脚本只需要ARGV参数
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //4.判断返回结果是否为0 即是否有下单资格
        if (result != null && !result.equals(0L)) {
            // 不为0 即没有下单资格
            log.info("没有下单资格");
            return Result.fail(result.intValue() == 1 ? "库存不足~" : "请勿重复下单~");
        }
        log.info("有下单资格");
        /* 改用使用消息队列处理
        //5.可以下单，封装信息为一个订单对象 订单id 用户id 优惠前id
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        //6.将订单对象加入到阻塞队列 即新建线程 异步处理 写入数据库 性能提升关键
        orderTasks.add(voucherOrder);
         */
        //7.获取事务代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //8.返回订单ID
        return Result.ok(orderId);
    }


    //函数重载 VoucherOrder voucherOrder 处理阻塞队列任务中 创建订单写入数据库
    @Transactional //有插入操作 添加事务
    public void creatVoucherOrder(VoucherOrder voucherOrder) {
        //从订单中获取用户id 优惠券id
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //获得指定用户的指定优惠券订单的数量
        Long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.info("一人一单,请勿重复下单~");
            return;
        }

        // 库存充足,扣减库存 加乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)//乐观锁，判断超卖（只要库存大于0就是安全的）
                .update();
        if (!success) {
            log.info("库存不足,请下次再来~");
            return;
        }
        //保存订单到数据库
        save(voucherOrder);
    }

/*
    @Transactional //有修改操作 添加事务
    public Result creatVoucherOrder(Long voucherId) {
        //4.一人一单
        //通过登录拦截器中的登录线程获取用户id
        Long userId = UserHolder.getUser().getId();
        //4.1 获得指定用户的指定优惠券订单的数量
        Long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("一人一单,请勿重复下单~");
        }

        //5.库存充足,扣减库存 加乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)//乐观锁，判断超卖（只要库存大于0就是安全的）
                .update();
        if (!success) {
            return Result.fail("库存不足,请下次再来~");
        }

        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 设置订单id 使用全局ID生成器
        long orderId = redisWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2 设置用户id
        voucherOrder.setUserId(userId);
        //6.3 设置优惠券id
        voucherOrder.setVoucherId(voucherId);
        //6.4 保存到数据库
        save(voucherOrder);
        //7.返回订单ID
        return Result.ok(orderId);
    }


 */
/*
    //实现优惠券秒杀
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断优惠券秒杀是否在当前时间内 是否过期
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {//还未开始
            return Result.fail("秒杀尚未开始,请耐心等待~");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {//已过期
            return Result.fail("秒杀已结束,请下次再来~");
        }
        //3.判断库存是否充足
        if (voucher.getStock() < 1) {//库存不足
            return Result.fail("库存不足,请下次再来~");
        }

        //通过登录拦截器中的登录线程获取用户id
        Long userId = UserHolder.getUser().getId();

        //4.使用Redis实现分布式锁 限制一人一单
        //4.1 获取锁对象 key使用用户id进行拼接
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //4.1改用redisson的分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //4.2 尝试获取锁 设置锁的过期时间
        boolean isLock = lock.tryLock();//默认参数为空
        //4.3 判断是否获取锁成功
        if (!isLock) {//获取锁失败
            return Result.fail("请勿重复下单~");
        }
        try {
            //获取代理对象（事务）防止事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.creatVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();//释放锁
        }
    }


    @Transactional //有修改操作 添加事务
    public Result creatVoucherOrder(Long voucherId) {
        //4.一人一单
        //通过登录拦截器中的登录线程获取用户id
        Long userId = UserHolder.getUser().getId();
        //4.1 获得指定用户的指定优惠券订单的数量
        Long count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("一人一单,请勿重复下单~");
        }

        //5.库存充足,扣减库存 加乐观锁
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)//乐观锁，判断超卖（只要库存大于0就是安全的）
                .update();
        if (!success) {
            return Result.fail("库存不足,请下次再来~");
        }

        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 设置订单id 使用全局ID生成器
        long orderId = redisWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2 设置用户id
        voucherOrder.setUserId(userId);
        //6.3 设置优惠券id
        voucherOrder.setVoucherId(voucherId);
        //6.4 保存到数据库
        save(voucherOrder);
        //7.返回订单ID
        return Result.ok(orderId);
    }


 */

}
