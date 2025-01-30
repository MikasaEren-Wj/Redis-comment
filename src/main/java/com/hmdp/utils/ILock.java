package com.hmdp.utils;

/**
 * ErenMikasa
 * Date 2024/10/22
 */
// Redis实现分布式锁
public interface ILock {
    boolean tryLock(Long timeout);//获取锁
    void unLock();  //释放锁
}
