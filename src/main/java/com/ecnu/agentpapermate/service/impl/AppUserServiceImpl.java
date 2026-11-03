package com.ecnu.agentpapermate.service.impl;

import com.ecnu.agentpapermate.entity.model.AppUser;
import com.ecnu.agentpapermate.mapper.AppUserMapper;
import com.ecnu.agentpapermate.service.AppUserService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ecnu.agentpapermate.entity.dto.user.UserUpdateRequest;
import com.ecnu.agentpapermate.entity.vo.user.LoginUserVO;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

import static com.ecnu.agentpapermate.constant.UserConstant.USER_LOGIN_STATE;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Service
public class AppUserServiceImpl extends ServiceImpl<AppUserMapper, AppUser> implements AppUserService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public Long userRegister(String username, String password, String checkPassword) {
        // 1、参数校验
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);
        ThrowUtils.throwIf(!password.equals(checkPassword), ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");

        // 2、检查账号是否重复
        ThrowUtils.throwIf(count(Wrappers.<AppUser>lambdaQuery()
                        .eq(AppUser::getUsername, normalizedUsername)) > 0,
                ErrorCode.PARAMS_ERROR, "账号已存在");

        // 3、构建用户信息并对密码进行哈希处理
        AppUser user = new AppUser();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));

        // 4、设置默认昵称和创建、更新时间
        user.setNickname("无名");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.setCreateTime(now);
        user.setUpdateTime(now);

        // 5、保存用户，处理并发注册导致的账号重复
        try {
            ThrowUtils.throwIf(!save(user), ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
        } catch (DuplicateKeyException e) {
            // 数据库唯一索引兜底并发注册和已逻辑删除账号的重复注册。
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }
        // 6、返回注册成功的用户 ID
        return user.getId();
    }

    @Override
    public LoginUserVO userLogin(String username, String password, HttpServletRequest request) {
        // 1、参数校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String normalizedUsername = normalizeUsername(username);
        validatePassword(password);

        // 2、查询用户并校验密码
        AppUser user = getOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, normalizedUsername));
        ThrowUtils.throwIf(user == null || !passwordEncoder.matches(password, user.getPasswordHash()),
                ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        // 3、轮换 Session ID 并保存登录态，Session 中仅保存用户 ID
        HttpSession session = request.getSession();
        request.changeSessionId();
        session.setAttribute(USER_LOGIN_STATE, user.getId());

        // 4、返回脱敏后的用户信息
        return getLoginUserVO(user);
    }

    @Override
    public boolean userUpdate(UserUpdateRequest updateRequest, HttpServletRequest request) {
        // 1、校验请求并获取当前登录用户
        ThrowUtils.throwIf(updateRequest == null, ErrorCode.PARAMS_ERROR);
        AppUser loginUser = getLoginUser(request);

        // 2、获取待更新字段并校验参数
        String nickname = updateRequest.getNickname();
        String avatarUrl = updateRequest.getAvatarUrl();
        String password = updateRequest.getPassword();
        ThrowUtils.throwIf(nickname == null && avatarUrl == null && password == null,
                ErrorCode.PARAMS_ERROR, "请至少提供一个需要更新的字段");
        if (nickname != null) {
            ThrowUtils.throwIf(!StringUtils.hasText(nickname) || nickname.length() > 100,
                    ErrorCode.PARAMS_ERROR, "昵称不能为空且不能超过100个字符");
        }
        if (avatarUrl != null) {
            ThrowUtils.throwIf(avatarUrl.length() > 1024,
                    ErrorCode.PARAMS_ERROR, "头像地址不能超过1024个字符");
        }
        if (password != null) {
            validatePassword(password);
        }

        // 3、构建允许更新的用户信息，ID 始终取自当前登录用户
        AppUser changes = new AppUser();
        changes.setId(loginUser.getId());
        changes.setNickname(nickname);
        changes.setAvatarUrl(avatarUrl);

        // 4、传入新密码时进行哈希处理
        if (password != null) {
            changes.setPasswordHash(passwordEncoder.encode(password));
        }
        // 5、设置更新时间并保存修改
        changes.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        ThrowUtils.throwIf(!updateById(changes), ErrorCode.OPERATION_ERROR, "用户信息更新失败");

        // 6、返回更新结果
        // Session 只存 ID，后续获取用户信息时读取数据库中的最新值。
        return true;
    }

    @Override
    public AppUser getLoginUser(HttpServletRequest request) {
        // 1、参数校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        // 2、读取 Session 并检查登录态
        HttpSession session = request.getSession(false);
        Object userId = session == null ? null : session.getAttribute(USER_LOGIN_STATE);
        if (!(userId instanceof Long)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 3、查询最新用户信息，用户不存在时清除失效的登录态
        AppUser user = getById((Long) userId);
        if (user == null) {
            session.removeAttribute(USER_LOGIN_STATE);
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 4、返回当前登录用户
        return user;
    }

    @Override
    public LoginUserVO getLoginUserVO(AppUser user) {
        // 1、检查用户是否为空
        if (user == null) {
            return null;
        }
        // 2、复制允许对外返回的字段，排除密码哈希及内部状态字段
        LoginUserVO result = new LoginUserVO();
        result.setId(user.getId());
        result.setUsername(user.getUsername());
        result.setNickname(user.getNickname());
        result.setAvatarUrl(user.getAvatarUrl());
        result.setCreateTime(user.getCreateTime());
        result.setUpdateTime(user.getUpdateTime());
        // 3、返回脱敏后的用户信息
        return result;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 1、参数校验
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);

        // 2、检查用户是否已登录
        HttpSession session = request.getSession(false);
        ThrowUtils.throwIf(session == null || session.getAttribute(USER_LOGIN_STATE) == null,
                ErrorCode.NOT_LOGIN_ERROR);
        // 3、使当前 Session 失效并退出登录
        session.invalidate();
        return true;
    }

    private String normalizeUsername(String username) {
        // 1、校验账号是否为空
        ThrowUtils.throwIf(!StringUtils.hasText(username), ErrorCode.PARAMS_ERROR, "账号不能为空");

        // 2、去除首尾空白并统一转为小写
        String normalized = username.strip().toLowerCase(Locale.ROOT);

        // 3、校验规范化后的账号长度
        ThrowUtils.throwIf(normalized.length() < 4 || normalized.length() > 64,
                ErrorCode.PARAMS_ERROR, "账号长度必须为4到64个字符");
        return normalized;
    }

    private void validatePassword(String password) {
        // 1、校验密码是否为空
        ThrowUtils.throwIf(!StringUtils.hasText(password), ErrorCode.PARAMS_ERROR, "密码不能为空");

        // 2、校验密码最小长度
        ThrowUtils.throwIf(password.length() < 6, ErrorCode.PARAMS_ERROR, "密码不能少于6个字符");

        // 3、校验密码的 UTF-8 字节长度
        // BCrypt 最多处理72字节，拒绝超长输入，避免密码被静默截断。
        ThrowUtils.throwIf(password.getBytes(StandardCharsets.UTF_8).length > 72,
                ErrorCode.PARAMS_ERROR, "密码不能超过72个UTF-8字节");
    }
}
