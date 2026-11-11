# AgentPaperMate 前端

基于原型视觉与交互实现的 Vue 3 + TypeScript 研究工作台。使用 Vite、Vue Router、Pinia、Axios、Lucide、Marked + DOMPurify 与 PDF.js。正式应用只请求 Java 后端，不包含原型论文数据或 Mock 开关。

2026-11-12 聊天页面已重构为宽幅阅读工作台：支持折叠导航、按需资料抽屉、紧凑执行摘要、正文引用定位和数学公式，多行输入与消息滚动区域分离。组件变更与验证边界见 [聊天 UI 重构记录](docs/chat-ui-refactor.md)。

2026-11-11 已适配后端 Agent 执行入口、技能选择和工具进度事件。聊天室可选择 RAG / Agent；此前接入的论文分页、详情、处理任务、PDF 文件与完整资料范围更新接口继续保留。当前功能、剩余接口和真实验证边界见 [对接状态](docs/frontend-backend-gaps.md)。本轮仅修改前端，不包含评测功能。

## 启动

建议使用 Node.js 22.13+ 或 24 LTS。本机验证环境为 Node.js 24.15.0。

```powershell
cd D:\project\JAVA\step-13\AgentPaperMate\frontend
npm ci
npm run dev
```

打开 http://127.0.0.1:5173 。Java 服务默认运行在 http://localhost:8123/api 。开发模式由 Vite 代理，浏览器地址始终同源，Cookie 登录与 SSE 共用认证。

可将 `.env.example` 复制为 `.env.local`：

```dotenv
VITE_API_BASE_URL=/api
API_PROXY_TARGET=http://localhost:8123
```

- `VITE_API_BASE_URL` 含一次 `/api`；API 模块只写 `/appUser/get` 等相对路径。
- `API_PROXY_TARGET` 只含后端 origin，不追加 `/api`。配置修改后重启 Vite。
- 不存储自造 Token，不把密码放在环境变量里。后端 Spring Session 使用 HttpOnly Cookie。
- 字体优先使用 DM Sans、Noto Sans SC；未安装时回退到系统中文字体，无外部字体 CDN 依赖。

## 当前功能

| 模块 | 可用能力 | 受后端限制的部分 |
| --- | --- | --- |
| 认证/资料 | 注册、登录、退出、路由保护、昵称/头像/密码更新 | 无 |
| 总览 | 真实论文与处理状态计数、最近论文、会话统计和最近会话、研究问题入口 | 全库主题/页数与精确可问答数量聚合 |
| 论文库 | 标题/作者/主题/状态筛选、分页、跨页选择、PDF 上传和处理状态、创建/加入聊天室 | 解析失败手动重试 |
| 阅读 | 元数据与摘要、受权限保护的 PDF 获取、PDF.js 翻页/缩放/物理页定位、任务轮询、六字段笔记与版本冲突保护、导出 | 会议/期刊字段尚未提供 |
| 聊天 | 会话分页/搜索/创建/切换/重命名/删除、草稿持久化、历史消息、完整 Markdown 导出 | 清空消息 |
| 资料范围 | 全库筛选与跨页勾选、已选列表、完整集合原子提交、空范围、版本冲突、canAsk 检查 | 无；最大数量和权限仍由后端校验 |
| 问答 | RAG / Agent 模式选择、技能与工具执行进度、幂等 Run 创建、具名 SSE、补发/快照/轮询恢复、终态、取消、重新执行历史问题 | 需要后端问答开关和已索引的真实论文；Agent 总结目前仅支持单篇论文 |
| 引用 | 后端保存的标题、物理页码、摘录、位置、证据 ID，以及 PDF 实际页码跳转 | 需要后端可读取对应的原始文件 |

笔记冲突时停止自动保存，保留当前编辑，可先导出草稿再加载最新版本。离开有未保存内容的页面会先尝试保存，失败时明确提示。新上传论文不会自动加入旧会话。

论文选择在确认前仅存在于当前界面，取消不会保存。范围确认提交完整 `paperIds` 和读取到的 `scopeVersion`；冲突时需要重新读取并确认。聊天前依据详情 `canAsk` 检查全部资料，不能仅凭论文状态 `ready` 判断可问答。PDF 当前完整下载后渲染，后端 Range 能力预留用于后续大文件优化。

`npm run dev/build` 会自动将 PDF.js 的 CMap、标准字体和 WASM 复制到 `public/pdfjs/`。这些生成资产无需提交 Git，生产构建会包含它们。

SSE 断开只结束订阅，不取消任务；任务取消调用真实接口。首次创建响应不明时保留内存中的原 requestKey，用户确认结果时重发相同请求。刷新后从会话 activeRunId 恢复运行任务，已完成结果从历史消息读取。发送恢复信息不作为业务持久化数据源，不使用 `apm_rooms_v1` 或 `apm_notes_v1`。

回答模式默认 RAG，创建 Run 时显式传 `executorType: rag | agent`。Agent 技能由后端选择，前端不提交技能 ID。单篇资料时提供结构化总结快捷问题，多篇资料时提示总结范围限制。提交中、结果待确认或任务执行中锁定模式；不明确的提交重发保留原模式、问题和幂等键。主动重新执行历史问题使用当前选择的模式和当前资料范围，并在确认框中说明。

执行过程展示 `skill_selected` 和 `tool_started/tool_completed/tool_failed`；按调用 ID 合并开始与结果，展示工具名称、状态、耗时及错误码。工具失败不等同于任务失败，终态以 Run 为准。刷新时从事件接口恢复当前或最近一次任务的进度，超过 100 条事件会连续补发。任务结束后仍未收到结果的工具标记为结果未确认，不继续显示执行动画。旧任务没有技能/工具事件时不推断其模式。

## 目录

```text
frontend/
├─ src/
│  ├─ api/                 # Axios、BaseResponse、已有 API
│  ├─ components/          # 笔记、上传、消息、资料范围、PDF 组件
│  ├─ composables/         # 聊天状态与异步生命周期
│  ├─ layouts/             # 原型侧栏/顶栏布局
│  ├─ pages/               # 登录、总览、论文库、阅读、聊天、资料
│  ├─ router/              # 路由与认证保护
│  ├─ stores/              # 当前用户、内存幂等恢复信息
│  ├─ styles/              # 原型样式与适配
│  ├─ types/               # 与 Java DTO/VO 对应的类型
│  └─ utils/               # SSE、Markdown 清理、导出与格式化
├─ tests/unit/             # 请求协议与 SSE 恢复测试
├─ tests/e2e/              # 浏览器交互及可选真实服务联调
├─ docs/                   # 接口缺口与验证范围
├─ .env.example
├─ playwright.config.ts
├─ vite.config.ts
└─ package-lock.json
```

## 验证

```powershell
npm run typecheck
npm test
npm run build

# 使用本机已有 Chrome/Chromium，或先安装 Playwright Chromium
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE = '本机 chrome.exe 的完整路径'
npm run test:e2e

# 可选：真实服务联调（需要已启动后端）
# 会创建独立 frontend_qa_ 测试账号；测试会话在完成后删除。
$env:APM_LIVE_TEST = '1'
npm run test:e2e -- live.spec.ts
```

测试使用独立浏览器上下文，不读取日常浏览器账号。测试专用响应仅存在 `tests/e2e/frontend.spec.ts` 中；正式代码没有导入它们。真实联调不上传论文或触发大模型收费调用。

已完成验证范围和真实联调边界见 [接口缺口文档](docs/frontend-backend-gaps.md#已验证与未验证)。浏览器测试覆盖 1920、1440、1280、768、390 像素布局，截图与失败追踪写入被忽略的 `test-results/`。

## 生产部署

`npm run build` 输出 `dist/`。静态站点需要 history fallback（未知非 API 路径回退到 `index.html`）。生产环境推荐同源反向代理 `/api` 至 Java 服务，保留 Cookie 并关闭 SSE 响应缓冲。例如 Nginx：

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
location /api/ {
    proxy_pass http://127.0.0.1:8123;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_buffering off;
    proxy_read_timeout 360s;
    client_max_body_size 51m;
}
```

若使用跨源 API，需要后端明确配置允许的前端 origin、凭据和 Cookie 属性。`npm run preview` 仅预览静态产物，不自动提供开发 API 代理；完整联调使用 `npm run dev` 或上述生产反向代理。
