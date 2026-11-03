package com.ecnu.agentpapermate.entity.dto.user;

import lombok.Getter;
import lombok.Setter;

/**
 * 当前登录用户的信息更新请求，仅允许修改昵称、头像地址和密码。
 * 各字段为 null 时表示不修改，至少提供一个待更新字段。
 */
@Getter
@Setter
public class UserUpdateRequest {

    /**
     * 昵称，不能全为空白且不能超过 100 个字符。
     */
    private String nickname;

    /**
     * 头像地址，不能超过 1024 个字符，空字符串表示清空头像。
     */
    private String avatarUrl;

    /**
     * 新密码，至少 6 个字符，UTF-8 编码后不超过 72 字节，保存前进行哈希处理。
     */
    private String password;
}
