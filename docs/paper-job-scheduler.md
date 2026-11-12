# PDF 数据库任务调度

手动重新处理：`POST /paper/retry`，JSON 请求体为 `{ "id": "论文ID", "jobId": "失败任务ID" }`，登录身份从会话获取。仅允许本人未回收论文的最新失败任务创建下一索引版本，复用原 PDF，使用当前处理配置和全新自动重试预算。论文行锁串行化并发请求；旧任务 ID 的重复请求返回最新任务，不再次创建。返回结构与 `/paper/add` 相同。保留旧任务记录及已有有效索引，无需数据库迁移。重复导入同内容 PDF 返回最新任务；前端详情页和上传结果中的失败状态提供“重新处理”，提交后跟踪新任务。

任务获取与调度已接通完整入库流程，见 [GROBID 入库说明](./paper-ingestion.md)。上传保存事务创建 `paper`、`paper_processing_job`，不再创建 Outbox；不需要 RabbitMQ。新数据库使用 `sql/create_table.sql`，已有库沿用重试和租约字段，新增有效尝试字段的 SQL 见入库说明，旧 Outbox 表保留历史数据即可。

调度器先取得本实例 Semaphore 名额，再在 READ COMMITTED 短事务内使用 `FOR UPDATE SKIP LOCKED` 领取到期 `pending` 任务，提交后使用虚拟线程调用 `PaperJobProcessor.process`。领取范围排除逻辑删除的论文和任务；每次领取生成新令牌。异常和过期租约使用条件更新回队，重试预算耗尽进入 `failed`。

`process` 调用 GROBID、Spring AI 和 ES。pipeline 默认关闭，关闭时不领取任务；启用后周期续约，验证索引成功后原子更新任务和论文终态，不再使用空流程回队。

配置位于 `application.yml` 的 `paper.processing.scheduler`：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| enabled | true | 是否启用扫描和执行器 |
| poll-interval-ms | 2000 | 每轮扫描完成后等待的毫秒数 |
| max-concurrent-jobs | 4 | 每实例最大同时执行任务数 |
| batch-size | 16 | 单轮最多领取及回收的任务数 |
| lease-seconds | 120 | 领取租约有效秒数 |
| retry-delay-seconds | 30 | 异常及租约过期后的固定重试延迟 |

时间字段取 MySQL 会话的上海时间（+08:00）。任务领取、租约和重试均使用 `CURRENT_TIMESTAMP(3)`。`retry_count` 表示已经安排的额外重试次数，`max_retries=3` 最多允许首次加三次重试。日志级别设为 `logging.level.com.ecnu.agentpapermate.scheduler=DEBUG` 可查看领取记录。

从旧版切换时，先暂停上传并让旧任务完成，再切换所有后端实例。若仍有待重试或运行中的任务，请先核对其 `next_retry_time`、`start_time`、`lease_until` 的时区，否则旧 UTC 值可能被新调度器提前判为到期。历史 `DATETIME` 不含时区标记，只对确认按 UTC 写入的值加 8 小时。

## 验证

无需外部服务的针对性测试：

```powershell
mvn '-Dtest=PaperServiceTest,PaperProcessingPropertiesTest,PaperJobSchedulerTest,PaperJobClaimServiceTest,PaperJobSchedulerConfigurationTest,PaperJobMapperConfigurationTest' test
```

`MySqlPaperJobMapperTest` 需显式设置环境变量 `PAPER_JOB_TEST_JDBC_URL`、`PAPER_JOB_TEST_USERNAME`、`PAPER_JOB_TEST_PASSWORD` 后运行 `mvn '-Dtest=MySqlPaperJobMapperTest' test`。只在单个连接中创建同名临时表来隔离永久业务表，验证当前建表语句及真实 Mapper SQL，包括到期条件、旧令牌、过期租约和重试上限。连接关闭即清理；无需清空业务库。该测试不替代多个进程同时领取的压力测试。

完整应用上下文测试已关闭扫描，避免测试启动时领取开发环境中的任务。
