package com.ecnu.agentpapermate.service.impl;

import com.ecnu.agentpapermate.entity.vo.run.RunView;
import com.ecnu.agentpapermate.entity.vo.run.RunEvaluationView;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.mapper.AgentRunMapper;
import com.ecnu.agentpapermate.service.AgentRunService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import com.ecnu.agentpapermate.entity.dto.run.RunCreateRequest;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.ecnu.agentpapermate.service.run.rag.RunQueries;

/**
 * <p>
 * 单次问答执行任务 服务实现类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Service
@RequiredArgsConstructor
public class AgentRunServiceImpl extends ServiceImpl<AgentRunMapper, AgentRun> implements AgentRunService {
    /**
     * 任务事务服务，负责幂等创建任务及原子处理取消请求。
     */
    private final RunStore store;
    /**
     * 任务查询服务，读取状态、错误和已持久化的最终回答及引用。
     */
    private final RunQueries queries;

    @Override
    public Long createRun(RunCreateRequest request, Long userId) {
        //1、由独立事务服务提交任务，后台扫描已提交的 pending 任务
        return store.create(request, userId);
    }

    @Override
    public RunView getRun(Long id, Long userId) {
        //1、验证归属并读取已持久化结果
        return queries.view(id, userId);
    }

    @Override
    public RunEvaluationView getEvaluation(Long id, Long userId) {
        return queries.evaluation(id, userId);
    }

    @Override
    public String cancelRun(Long id, Long userId) {
        //1、取消与完成在同一任务锁下竞争，已结束状态不会被覆盖
        return store.cancel(id, userId);
    }
}
