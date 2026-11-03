package com.ecnu.agentpapermate.entity.vo.user;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外返回的登录用户信息，不包含密码哈希及内部状态字段。
 */
@Data
public class LoginUserVO {

    /**
     * 用户 ID。
     */
    private Long id;

    /**
     * 登录账号。
     */
    private String username;

    /**
     * 显示昵称。
     */
    private String nickname;

    /**
     * 头像地址。
     */
    private String avatarUrl;

    /**
     * 创建时间，使用 UTC。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间，使用 UTC。
     */
    private LocalDateTime updateTime;
}
