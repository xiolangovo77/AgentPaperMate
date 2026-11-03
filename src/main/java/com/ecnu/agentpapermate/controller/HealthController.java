package com.ecnu.agentpapermate.controller;

import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("/health")
public class HealthController {

    @GetMapping
    public BaseResponse<String> health(){
        return ResultUtils.success("ok");
    }
}
