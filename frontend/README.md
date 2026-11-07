# AgentPaperMate 前端

基于原型视觉与交互实现的 Vue 3 + TypeScript 研究工作台。使用 Vite、Vue Router、Pinia、Axios、Lucide、Marked + DOMPurify；预备 PDF.js 阅读组件。正式应用只请求 Java 后端，不包含原型论文数据或 Mock 开关。

本次仅开发前端。**现有后端尚缺论文查询、状态、PDF 读取和批量范围更新接口，无法宣称已完成完整论文问答业务闭环。** 详见 [接口核对与缺口](docs/frontend-backend-gaps.md)。

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
| 总览 | 欢迎区、真实会话统计、最近会话、研究问题入口 | 论文/主题/页数统计 |
| 论文库 | PDF 格式/50 MB 校验、实际上传进度、提交解析、回执、按 ID 进入笔记 | 全库列表、搜索筛选、处理状态、批量选论文 |
| 阅读 | 六字段笔记、自动/手动保存、版本冲突保留草稿、Markdown 导出 | 元数据、真实 PDF 阅读；阅读器组件预备就绪 |
| 聊天 | 会话分页/搜索/创建/切换/重命名/删除、草稿持久化、历史消息、完整 Markdown 导出 | 清空消息 |
| 资料范围 | 当前范围、ID 筛选和分页、逐篇按 ID 添加/移出 | 全库选择器、完整集合原子更新、前置就绪状态查询 |
| 问答 | 幂等 Run 创建、具名 SSE、补发/快照/轮询恢复、终态、取消、重新执行历史问题 | 需要后端问答开关和已索引的真实论文 |
| 引用 | 后端保存的标题、物理页码、摘录、位置、证据 ID、目标路由 | 原文文件读取与实际页码跳转 |

笔记冲突时停止自动保存，保留当前编辑，可先导出草稿再加载最新版本。离开有未保存内容的页面会先尝试保存，失败时明确提示。新上传论文不会自动加入旧会话。

SSE 断开只结束订阅，不取消任务；任务取消调用真实接口。首次创建响应不明时保留内存中的原 requestKey，用户确认结果时重发相同请求。刷新后从会话 activeRunId 恢复运行任务，已完成结果从历史消息读取。发送恢复信息不作为业务持久化数据源，不使用 `apm_rooms_v1` 或 `apm_notes_v1`。

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
