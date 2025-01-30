package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

//为每个存储用户部分信息的线程提供了一个独立的变量副本，每个线程都可以独立地修改自己的副本，而不会影响其他线程
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
