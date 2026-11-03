package com.ecnu.agentpapermate.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.ecnu.agentpapermate.entity.dto.user.UserUpdateRequest;
import com.ecnu.agentpapermate.entity.model.AppUser;
import com.ecnu.agentpapermate.entity.vo.user.LoginUserVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户模块服务接口。
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
public interface AppUserService extends IService<AppUser> {

    /**
     * 注册用户，校验账号唯一性并保存密码哈希。
     *
     * @param username      登录账号，去除首尾空白后统一转为小写
     * @param password      原始密码
     * @param checkPassword 确认密码，必须与原始密码一致
     * @return 注册成功的用户 ID
     */
    Long userRegister(String username, String password, String checkPassword);

    /**
     * 校验账号密码，轮换 Session ID 并保存登录态。
     *
     * @param username 登录账号
     * @param password 原始密码
     * @param request  HTTP 请求，用于保存登录态
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String username, String password, HttpServletRequest request);

    /**
     * 更新当前登录用户的信息，仅允许修改昵称、头像地址和密码。
     *
     * @param updateRequest 更新请求，null 字段不修改，至少提供一个待更新字段
     * @param request       HTTP 请求，用于确定当前登录用户
     * @return 是否更新成功
     */
    boolean userUpdate(UserUpdateRequest updateRequest, HttpServletRequest request);

    /**
     * 根据 Session 中的用户 ID 查询最新用户信息，未登录或用户不存在时抛出业务异常。
     *
     * @param request HTTP 请求，用于读取登录态
     * @return 供服务端内部使用的用户实体，包含密码哈希，不应直接返回给客户端
     */
    AppUser getLoginUser(HttpServletRequest request);

    /**
     * 将用户实体转换为脱敏信息，排除密码哈希及内部状态字段。
     *
     * @param user 用户实体
     * @return 脱敏后的用户信息；传入 null 时返回 null
     */
    LoginUserVO getLoginUserVO(AppUser user);

    /**
     * 退出登录并使当前 Session 失效，未登录时抛出业务异常。
     *
     * @param request HTTP 请求，用于读取当前 Session
     * @return 是否退出成功
     */
    boolean userLogout(HttpServletRequest request);
}
