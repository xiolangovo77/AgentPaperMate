package com.ecnu.agentpapermate.service.run.rag;

import com.ecnu.agentpapermate.rag.model.Answer;
import com.ecnu.agentpapermate.rag.model.Evidence;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.validator.AnswerValidator;
import com.ecnu.agentpapermate.rag.RagAnswerGenerator;
import com.ecnu.agentpapermate.support.ai.RagCalls;
import com.ecnu.agentpapermate.rag.RagRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CancellationException;

/** 固定问答流程；数据库事务与外部服务调用始终分开。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunProcessor {
    /**
     * 任务事务服务，在各执行阶段核对状态并保存进度及最终结果。
     */
    private final RunStore store;
    /**
     * 任务查询服务，读取有长度预算的历史用户问题以理解追问。
     */
    private final RunQueries queries;
    /**
     * 论文检索器，完成问题向量化、ES 双路检索及证据融合。
     */
    private final RagRetriever retriever;
    /**
     * 回答生成器，根据本次证据调用模型并校验回答格式与引用。
     */
    private final RagAnswerGenerator generator;

    public void process(AgentRun run) {
        try {
            //1、提交检索开始事件后，执行问题向量化、ES 检索和证据融合
            if (!store.retrievalStarted(run.getId())) return;
            var retrieval = retriever.retrieve(run);
            if (!store.retrieved(run.getId(), retrieval)) return;
            List<Evidence> evidence = retrieval.evidence();

            //2、缺少可引用证据时给出明确说明，不让模型猜测或补齐答案
            Answer answer;
            if (evidence.isEmpty()) {
                answer = new Answer("无法从当前资料中找到具有可靠页码的有效证据，暂时无法确认该问题。请调整问题或检查论文解析结果。",
                        List.of());
            } else {
                if (!store.modelStarted(run.getId())) return;
                answer = generator.generate(run, evidence, queries.previousQuestions(run));
            }

            //3、保存经过校验的答案和引用，终态事务再次防止取消后的迟到写入
            store.complete(run.getId(), answer);
        } catch (RagCalls.RequestTimeout e) {
            store.fail(run.getId(), "REQUEST_TIMEOUT", "外部服务请求超时", true);
        } catch (AnswerValidator.InvalidAnswer e) {
            store.fail(run.getId(), "INVALID_CITATION", "模型回答格式或引用校验失败，请重新提问", false);
        } catch (CancellationException e) {
            //4、已取消任务保持原终态；异常中断且尚未结束的任务转为失败
            store.fail(run.getId(), "EXECUTION_INTERRUPTED", "任务执行被中断", false);
        } catch (BusinessException e) {
            store.fail(run.getId(), "SOURCE_UNAVAILABLE", "任务资料已不可访问", false);
        } catch (RuntimeException e) {
            //5、不把上游响应体、请求头或凭据写入事件及错误摘要
            log.warn("问答执行失败，runId={}，异常类型={}", run.getId(), e.getClass().getSimpleName());
            store.fail(run.getId(), "EXECUTION_FAILED", "检索或模型服务调用失败，请稍后重试", false);
        }
    }
}
