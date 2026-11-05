package com.ecnu.agentpapermate.entity.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体，对应 app_user 表。
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Getter
@Setter
@ToString
@TableName("app_user")
public class AppUser implements Serializable {

    /**
     * 序列化版本号。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID，由 MyBatis-Plus 分配雪花 ID。
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 登录账号，服务端去除首尾空白后统一转为小写。
     */
    @TableField("username")
    private String username;

    /**
     * BCrypt 密码哈希，不能保存明文密码。
     */
    @TableField("password_hash")
    private String passwordHash;

    /**
     * 显示昵称。
     */
    @TableField("nickname")
    private String nickname;

    /**
     * 头像地址。
     */
    @TableField("avatar_url")
    private String avatarUrl;

    /**
     * 用户状态：enabled 表示启用，disabled 表示禁用，当前暂不参与业务逻辑。
     */
    @TableField("status")
    private String status;

    /**
     * 创建时间，使用上海时间。
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间，使用上海时间。
     */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标记：0 表示正常，1 表示删除。
     */
    @TableLogic
    @TableField("is_delete")
    private Byte isDelete;
}
