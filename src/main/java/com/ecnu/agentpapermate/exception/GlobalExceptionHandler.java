package com.ecnu.agentpapermate.exception;

import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Hidden
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public BaseResponse<?> maxUploadSizeExceededExceptionHandler(MaxUploadSizeExceededException e) {
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "上传文件过大：头像不能超过 2 MB，论文不能超过 50 MB");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public BaseResponse<?> missingServletRequestPartExceptionHandler(MissingServletRequestPartException e) {
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "缺少上传文件参数：" + e.getRequestPartName());
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
