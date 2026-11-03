package com.ecnu.agentpapermate.controller;

import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.entity.dto.user.UserLoginRequest;
import com.ecnu.agentpapermate.entity.dto.user.UserRegisterRequest;
import com.ecnu.agentpapermate.entity.dto.user.UserUpdateRequest;
import com.ecnu.agentpapermate.entity.vo.user.LoginUserVO;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import com.ecnu.agentpapermate.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户模块控制器。
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/appUser")
@RequiredArgsConstructor
public class AppUserController {

    /**
     * 用户服务。
     */
    private final AppUserService appUserService;

    /**
     * 用户注册。
     *
     * @param registerRequest 注册请求，包含账号、密码和确认密码
     * @return 注册成功的用户 ID
     */
    @PostMapping("/register")
    public BaseResponse<Long> register(@RequestBody UserRegisterRequest registerRequest) {
        ThrowUtils.throwIf(registerRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appUserService.userRegister(registerRequest.getUsername(),
                registerRequest.getPassword(), registerRequest.getCheckPassword()));
    }

    /**
     * 用户登录并保存 Session 登录态。
     *
     * @param loginRequest 登录请求，包含账号和密码
     * @param request      HTTP 请求，用于保存登录态
     * @return 脱敏后的用户信息
     */
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> login(@RequestBody UserLoginRequest loginRequest,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(loginRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appUserService.userLogin(loginRequest.getUsername(),
                loginRequest.getPassword(), request));
    }

    /**
     * 更新当前登录用户的昵称、头像地址或密码。
     *
     * @param updateRequest 更新请求，未传入的字段保持不变
     * @param request       HTTP 请求，用于获取当前登录用户
     * @return 是否更新成功
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> update(@RequestBody UserUpdateRequest updateRequest,
                                        HttpServletRequest request) {
        return ResultUtils.success(appUserService.userUpdate(updateRequest, request));
    }

    /**
     * 用户退出登录并使当前 Session 失效。
     *
     * @param request HTTP 请求，用于获取当前 Session
     * @return 是否退出成功
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> logout(HttpServletRequest request) {
        return ResultUtils.success(appUserService.userLogout(request));
    }

    /**
     * 获取当前登录用户的最新信息。
     *
     * @param request HTTP 请求，用于获取当前登录用户
     * @return 脱敏后的用户信息
     */
    @GetMapping("/get")
    public BaseResponse<LoginUserVO> get(HttpServletRequest request) {
        return ResultUtils.success(appUserService.getLoginUserVO(appUserService.getLoginUser(request)));
    }
}
