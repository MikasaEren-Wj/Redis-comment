package com.hmdp.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

//用于实现使用逻辑过期的方法解决缓存击穿 存储缓存过期时间和数据对象
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
