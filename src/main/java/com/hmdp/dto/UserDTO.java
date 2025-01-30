package com.hmdp.dto;

import lombok.Data;

//用户信息数据脱敏 减少登录操作时服务器存储压力
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
