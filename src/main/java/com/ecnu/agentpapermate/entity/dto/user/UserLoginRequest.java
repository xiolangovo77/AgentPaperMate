package com.ecnu.agentpapermate.entity.dto.user;

import lombok.Getter;
import lombok.Setter;

/**
 * 用户登录请求。
 */
@Getter
@Setter
public class UserLoginRequest {

    /**
     * 登录账号，去除首尾空白后统一转为小写。
     */
    private String username;

    /**
     * 原始密码，用于校验数据库中的密码哈希。
     */
    private String password;
}
