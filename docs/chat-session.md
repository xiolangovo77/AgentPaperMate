# 聊天室与论文关联接口

以下路径相对于项目已有的 context-path，均需登录，用户ID取自登录态。
返回格式使用 `BaseResponse`；新增返回ID，更新返回 `true`，详情返回对应 Model，分页返回 MyBatis-Plus `Page`。
Long ID 按项目 JSON 配置返回字符串。

| 方法 | 路径 | 请求参数 |
| --- | --- | --- |
| POST | `/chatSession/add` | `{"name":"研究会话","inputDraft":"草稿"}`；传 `{}` 使用默认名称 |
| GET | `/chatSession/get` | `?id=聊天室ID` |
| POST | `/chatSession/list/page` | `{"pageNum":1,"pageSize":10,"name":"研究"}`；name 可选 |
| POST | `/chatSession/update` | `{"id":"聊天室ID","name":"新名称","inputDraft":""}` |
| POST | `/sessionPaper/add` | `{"sessionId":"聊天室ID","paperId":"论文ID"}` |
| GET | `/sessionPaper/get` | `?id=关联ID` |
| POST | `/sessionPaper/list/page` | `{"sessionId":"聊天室ID","pageNum":1,"pageSize":10}`；paperId 可选 |
| POST | `/sessionPaper/update` | `{"id":"关联ID","isDelete":1}`；0 恢复，1 移出 |

- 聊天室更新至少提供名称或草稿；null 表示不修改，空字符串草稿表示清空。名称去除首尾空白后必须为1至200个字符；草稿按 UTF-8 限制为65535字节。
- `userId`、`scopeVersion`、`activeRunId`、活动时间及系统时间不作为客户端可编辑字段。修改名称或保存草稿不改变 `last_message_time`。
- 两种分页均限制每页1至100条，页码从1开始。聊天室固定按 `last_message_time DESC, id DESC` 排序；关联固定按 `create_time DESC, id DESC` 排序。通用分页参数中的排序字段不生效。
- 详情和分页只返回未逻辑删除的记录。聊天室和论文必须属于当前用户；已删除聊天室不可访问，已删除论文不可添加或恢复。
- 添加已存在的有效关联直接返回原ID。添加已移出的论文恢复原记录，保留ID和首次创建时间；修改关联不能更换聊天室或论文ID。
- 论文关联新增、移出、恢复仅在集合实际变化时递增 `scope_version`，并更新 `last_message_time`。重复添加、重复移出、重复恢复不改变版本或活动时间。
- 关联变更与聊天室版本更新处于同一事务，通过先锁聊天室、再锁关联串行处理同一聊天室的并发修改。时间使用数据库当前时间，沿用项目连接的上海时区配置。

针对本模块的离线测试：

```shell
mvn -o -Dtest=ChatSessionServiceTest,SessionPaperServiceTest,ChatSessionMapperConfigurationTest test
```
