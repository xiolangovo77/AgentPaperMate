package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 限制外部调用等待时间；取消等待后不接受迟到结果。 */
@Component
public class RagCalls {
    /**
     * 外部调用虚拟线程执行器，使调用方能够限时等待并请求中断底层操作。
     */
    private final ExecutorService executor;
    /**
     * 问答运行配置，提供每次外部请求的最长等待时间。
     */
    private final RagProperties properties;
    /**
     * 任务时钟，用于计算本次外部调用可使用的剩余运行时间。
     */
    private final Clock clock;

    public RagCalls(@Qualifier("ragCallExecutor") ExecutorService executor, RagProperties properties, Clock ragClock) {
        this.executor = executor; this.properties = properties; this.clock = ragClock;
    }

    public <T> T call(AgentRun run, Supplier<T> operation) {
        //1、每次调用都同时遵守请求超时和整个 Run 的剩余预算
        long remaining = Duration.between(LocalDateTime.now(clock), run.getDeadline()).toMillis();
        long budget = Math.min(remaining, properties.getRequestTimeoutSeconds() * 1000L);
        if (budget <= 0) throw new RequestTimeout();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("任务已停止");
        Future<T> future = executor.submit(operation::get);
        try {
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RequestTimeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("任务已停止");
        } catch (ExecutionException e) {
            //2、底层 socket 超时可能先于 Future 超时返回，同样归类为 timeout
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException
                        || cause instanceof org.apache.http.conn.ConnectTimeoutException || cause instanceof TimeoutException) {
                    throw new RequestTimeout();
                }
            }
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("外部服务调用失败", e.getCause());
        } finally {
            //3、尽可能中止底层调用；即使底层无法中止，也不会接收其迟到结果
            future.cancel(true);
        }
    }

    public static class RequestTimeout extends RuntimeException {
        public RequestTimeout() { super("外部请求或问答总预算超时"); }
    }
}
