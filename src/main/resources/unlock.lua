-- 编写lua脚本实现Redis分布式锁的释放 保证释放过程的原子性
-- 获取锁的value(即标识) 判断是否与当前线程的标识一致
-- KEYS就对应Redis的key，ARGV对应值
if(redis.call('GET', KEYS[1]) == ARGV[1]) then
    -- 一致 则释放锁
    return redis.call('DEL', KEYS[1])
end
-- 不一致，则返回
return 0
