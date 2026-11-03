package com.ecnu.agentpapermate.entity.dto.user;

import lombok.Getter;
import lombok.Setter;

/**
 * 用户注册请求，仅包含账号、密码和确认密码。
 */
@Getter
@Setter
public class UserRegisterRequest {

    /**
     * 登录账号，去除首尾空白后长度为 4 到 64 个字符，统一转为小写。
     */
    private String username;

    /**
     * 原始密码，至少 6 个字符，UTF-8 编码后不超过 72 字节。
     */
    private String password;

    /**
     * 确认密码，必须与原始密码一致。
     */
    private String checkPassword;
}
