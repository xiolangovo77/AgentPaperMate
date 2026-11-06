package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.vo.run.RunView;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ecnu.agentpapermate.entity.dto.run.RunCreateRequest;

/**
 * <p>
 * 单次问答执行任务 服务类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
public interface AgentRunService extends IService<AgentRun> {
    /** 幂等创建问答任务。 */
    Long createRun(RunCreateRequest request, Long userId);
    /** 查询所属任务及最终结果。 */
    RunView getRun(Long id, Long userId);
    /** 请求取消，已结束任务保持原状态。 */
    String cancelRun(Long id, Long userId);
}
