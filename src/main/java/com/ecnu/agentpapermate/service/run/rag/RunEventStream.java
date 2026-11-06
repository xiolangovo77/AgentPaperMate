package com.ecnu.agentpapermate.service.run.rag;

import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 从已提交事件表补发 SSE；断开连接只结束订阅，不取消 Run。 */
@Service
public class RunEventStream {
    /**
     * 任务查询服务，读取已提交事件并在事件缺失时提供任务快照。
     */
    private final RunQueries queries;
    /**
     * 任务状态服务，验证订阅权限并判断是否已进入终态。
     */
    private final RunStore store;
    /**
     * SSE 订阅虚拟线程执行器，负责事件补发和心跳发送。
     */
    private final ExecutorService executor;
    /**
     * 本实例的 SSE 连接许可，限制同时存在的事件订阅数量。
     */
    private final Semaphore connections;

    public RunEventStream(RunQueries queries, RunStore store, @Qualifier("ragSseExecutor") ExecutorService executor,
                          RagProperties properties) {
        this.queries = queries; this.store = store; this.executor = executor;
        this.connections = new Semaphore(properties.getMaxSseConnections());
    }

    public SseEmitter subscribe(Long runId, Long after, Long userId) {
        //1、在切换到 SSE 响应前完成权限与游标检查，限制本实例连接数
        queries.validateCursor(after, store.owned(runId, userId));
        if (!connections.tryAcquire()) throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, "事件连接数量已达上限");
        SseEmitter emitter = new SseEmitter(300000L);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> { closed.set(true); emitter.complete(); });
        emitter.onError(error -> closed.set(true));
        try {
            executor.execute(() -> stream(emitter, closed, runId, after, userId));
        } catch (RuntimeException e) {
            connections.release();
            throw e;
        }
        return emitter;
    }

    private void stream(SseEmitter emitter, AtomicBoolean closed, Long runId, long cursor, Long userId) {
        long heartbeat = 0;
        try {
            //1、按序读取已提交事件，自然覆盖初次订阅、断线补发和跨实例订阅
            while (!closed.get()) {
                var batch = queries.events(runId, cursor, userId);
                for (var event : batch) {
                    if (closed.get()) return;
                    if (event.eventId() != cursor + 1) {
                        emitter.send(SseEmitter.event().name("run_snapshot").data(queries.view(runId, userId)));
                        emitter.complete();
                        return;
                    }
                    emitter.send(SseEmitter.event().id(event.eventId().toString()).name(event.type()).data(event));
                    cursor = event.eventId();
                }
                var run = store.owned(runId, userId);
                if (RunStore.terminal(run.getStatus()) && cursor >= run.getEventSeq()) {
                    emitter.complete();
                    return;
                }
                //2、事件过期时提供快照，正常等待时发送心跳，避免连接长期静默
                if (batch.isEmpty() && cursor < run.getEventSeq()) {
                    // 新事件可能在本轮查询后刚提交；当前这一轮立即重查一次，不能误判为过期。
                    var retry = queries.events(runId, cursor, userId);
                    if (retry.isEmpty()) {
                        emitter.send(SseEmitter.event().name("run_snapshot").data(queries.view(runId, userId)));
                        emitter.complete();
                        return;
                    }
                }
                if (System.nanoTime() - heartbeat > 15_000_000_000L) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    heartbeat = System.nanoTime();
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception e) {
            //3、客户端关闭或权限失效仅关闭订阅，任务继续由后台执行和回收
            emitter.completeWithError(e);
        } finally {
            connections.release();
        }
    }
}
