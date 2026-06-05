# 前端任务书：阶段 23 Settings 联系方式与头像存储状态契约同步

本文档是前端 Agent 的固定入口。前端 Agent 开始实现前必须先阅读 `AGENTS.md`、`doc/STAGE_PLAN.md`、`doc/PROJECT.md`、`doc/API.md` 和本文档。

## 当前阶段状态

**阶段 23 前端 Settings 联系方式、头像上传可用性、真实 OSS 上传回显、Chat/Documents/Settings Bug 修复和移动端 smoke 收尾已完成。**

阶段 23 前端目标是让 Settings 账号资料和后端 Stage23 契约一致：展示并编辑联系方式 `phone`，读取 `avatarStorageConfigured` 决定是否允许上传本地头像，OSS 未配置时提示“头像上传需要先配置 OSS；当前可选择默认头像”；OSS 已配置时上传真实头像并通过后端鉴权头像内容代理获取 blob 回显。复杂阶段和多子系统任务推荐采用团队编排模式：主对话担任项目经理/协调者，负责审核、拆分、下发、集成和最终验证；前端实现或复核任务优先拆分到 Codex 后台 Thread/worktree 或 `AGENT_TEAM`，且下发 worker 的思考/推理等级默认使用可用最高级（例如 `xhigh` / 最高级）。

## API 接入

继续使用现有 axios wrapper：

- `frontend/src/api/auth.ts`
- `frontend/src/api/chat.ts`
- `frontend/src/api/documents.ts`

阶段 21 技术例外仍保留：

- `frontend/src/api/chat-stream.ts` 是唯一允许直接使用 `fetch` 的前端文件，用于浏览器读取 `POST + text/event-stream`。其他普通 API 不得新增直接 `fetch`。

新增/扩展类型字段：

```ts
type AvatarSource = "UPLOAD" | "PRESET" | "NONE";
type DefaultAvatarPresetId = "blue" | "green" | "coral" | "violet" | "mint" | "rose" | "amber" | "slate";

type UserResponse = {
  id: number;
  username: string;
  role: string;
  email: string | null;
  phone: string | null;
  avatarUrl: string | null;
  avatarConfigured: boolean;
  avatarStorageConfigured: boolean;
  avatarSource: AvatarSource;
  avatarPresetId: DefaultAvatarPresetId | null;
};
```

二次验收后端契约补充：

```ts
updateCurrentUserProfile(payload: {
  username?: string;
  email?: string | null;
  phone?: string | null;
})
```

`username`、`email` 和 `phone` 均为可选字段，但请求体至少包含一个字段；`email: null` 或空字符串表示清空邮箱，`phone: null` 或空字符串表示清空联系方式。响应仍为 `UserResponse`，不得把 OSS object key、OSS 直连 URL、OSS 鉴权查询参数、密钥或 Authorization 暴露到 toast、日志、DOM 或 storage。

新增 API wrapper：

```ts
selectCurrentUserAvatarPreset(avatarPresetId: DefaultAvatarPresetId)
uploadCurrentUserAvatar(file)
getCurrentUserAvatarBlob(avatarUrl)
deleteCurrentUserAvatar()
```

`getCurrentUserAvatarBlob(avatarUrl)` 只接受同源后端头像内容代理路径，拒绝外部 `http(s)` URL；`deleteCurrentUserAvatar()` 仅保留兼容能力，Settings 普通用户入口不展示删除头像按钮。

## 已完成前端实现

1. Settings 资料与头像
   - 二次验收后，账号资料编辑恢复为可修改用户名、邮箱、联系方式和头像；角色、时区仍只读。
   - Settings 页面和编辑资料弹窗不再展示语言字段。
   - 新增“编辑资料”弹窗；弹窗中允许选择默认头像、选择本地头像文件、修改用户名、邮箱和联系方式。
   - 保存资料按钮移动到弹窗底部。
   - 删除“删除头像”用户入口；用户可通过选择默认头像或重新上传替换头像。
   - 新增 `frontend/src/components/user-avatar.tsx`，统一 `UserAvatar`、`AvatarPresetPicker` 和 8 个默认头像 preset。
   - Header、Sidebar、Settings 统一按 `avatarSource/avatarPresetId/avatarUrl` 渲染头像；上传头像通过 axios 读取后端代理 blob 后渲染为 `blob:` URL。
   - 阶段 23 增加 `avatarStorageConfigured` 判断：OSS 未配置时禁用本地头像上传，显示“头像上传需要先配置 OSS；当前可选择默认头像”，默认头像 preset 仍可保存。
   - Zustand auth store 运行时保留后端代理 `avatarUrl`，持久化时剔除 `avatarUrl`，避免把头像回显地址写入 storage。
   - 主布局挂载时通过 `/api/auth/me` 重新同步当前用户资料，刷新任意已登录页面后可重新获取上传头像回显地址。

2. Chat mention 交互
   - mention 触发改为 CLI 风格：只有 `@` 位于文本开头或前一个字符为空白时才打开文档列表。
   - mention 列表不自动聚焦搜索框，焦点保留在 textarea。
   - `@` 后继续输入的 inline query 用于过滤文档。
   - 文档名高亮匹配 query。
   - `↑/↓` 切换选中项，`←/→` 分页，`Enter/Tab` 选择，`Esc` 关闭。
   - 删除 `@` 后关闭列表；移除 mention chip 时删除整段 `@文档名` token；手动删改 token 后同步清理对应文档 ID。

3. Chat 验收修复
   - 流式提示文案改为“正在思考”。
   - 修复 Chat 引用来源和会话列表 mojibake 文案。
   - 点击当前会话沟通区域时，如果会话未读，则用防抖调用 `PATCH /api/chat/sessions/{id}` 设置 `unread:false`；已读状态不重复请求。
   - Settings 弹窗、头像选择、Chat composer、mention picker、会话列表、引用来源卡片增加 `min-w-0`、换行/截断和小屏布局约束。

4. 本轮 Chat / Documents / Settings Bug 修复
   - `/Chat/{sessionId}` 消息区 `scroll`、`pointerdown/click` 和焦点进入都会触发当前未读会话标记已读；仍复用防抖和请求进行中保护。
   - 流式输出自动跟随只在用户仍贴近底部或刚发送自己消息时启用；用户上滚后不再被 `delta`、`sources`、`done` 拉回底部。
   - 删除“流式生成 · 会结合当前会话上下文”文案，缩小 Chat header。
   - `ChatComposer` 删除旧放大/缩小按钮，改为顶部拖拽手柄调整输入框高度，范围约 `72px` 到 `40svh`，不写 `localStorage`。
   - `/Documents/{knowledgeBaseId}` 上传前按当前知识库已有 `originalFilename` 和同一批次文件名做 trim + 大小写不敏感重复校验；重复项 toast 提示“当前知识库已存在同名文档，请重命名后再上传。”。
   - `/Settings` 模型测试不强制重新填写已保存 API Key；测试请求只发送当前表单中有意义的 Base URL、API Key、Model，错误显示继续走脱敏消息。
   - Dashboard、KnowledgeBases、Documents、Jobs、Chat、Settings、Login 的移动端 smoke 重点修复横向溢出、弹窗宽度、按钮挤压和表单换行问题。

5. 本轮移动端响应式兼容与 Chat 重构
   - `/KnowledgeBases` 手机端 tabs 改为横向可滚动 pill tab，避免“精选知识库”换行压到工具栏；搜索、视图切换、排序和新建按钮改为小屏独立工具栏。
   - `/KnowledgeBases` 精选知识库区域在小屏下收紧标题、间距和卡片栅格，保持桌面端 featured 四列布局不变。
   - `/Chat/{sessionId}` 手机端改为消息优先布局；会话列表和引用来源默认不占用主屏高度，分别通过底部 Sheet 打开，桌面端仍保留左会话、中消息、右引用三栏结构。
   - `ChatComposer` 手机端输入框最大高度改为更保守的视口比例，RAG/模型、字数和发送按钮分行展示，mention picker 限制在输入区宽度内。
   - `/Settings` 账号资料卡片删除用户指定的账号资料说明文案，不影响编辑资料弹窗和头像/联系方式能力。

## UI 和交互要求

- Settings 资料卡只读，不静默新增 mock-only 保存。
- Settings 显示联系方式；联系方式可留空，只允许数字、普通空格、`+`、`-` 和英文括号。
- Settings 不展示“删除头像”按钮。
- 头像上传不把文件、OSS 直连 URL、OSS 鉴权查询参数、OSS object key 或 OSS 配置写入 localStorage/sessionStorage、DOM、toast 或 console。
- OSS 未配置时，前端不发起本地头像上传，并提示“头像上传需要先配置 OSS；当前可选择默认头像”。
- Chat mention 不抢焦点，不影响用户继续输入。
- 普通正文中的 `@` 不弹文档索引列表。
- API Key、Authorization、OSS 密钥、完整供应商错误不得出现在 toast、日志或错误详情中。
- 文档重复上传提示必须使用统一文案“当前知识库已存在同名文档，请重命名后再上传。”；部分文件重复时，只上传非重复文件。
- Chat 输入框高度只在当前页面生命周期内生效，不新增组件级 `localStorage` 持久化。
- 前端实现线程默认先完成代码级验证；当用户明确要求或协调者下发专门验收任务时，可以使用 `browser-use`、Browser、Chrome、Playwright、截图等浏览器工具做真实页面/UI 验收，并在回报中列出访问路径、操作步骤、观察结果、失败点和剩余人工验收项。

## 验证命令

阶段收尾运行：

```powershell
cd D:\Studio\MyWork\frontend
pnpm exec eslint src/pages/Settings.tsx src/api/auth.ts src/store/auth.ts src/components/settings src/components/chat src/components/chat-page src/components/slider-sidebar.tsx src/components/MainHeader.tsx src/components/user-avatar.tsx
pnpm exec eslint src/pages/Chat.tsx src/pages/Documents.tsx src/pages/Settings.tsx src/components/chat src/components/chat-page src/components/documents src/components/settings src/components/slider-layout.tsx src/components/slider-sidebar.tsx src/components/MainHeader.tsx
pnpm build
rg -n "<Settings 账号资料指定说明文案残留正则>" ..\frontend\src ..\doc
rg -n "fetch\(" src
rg -n "流式生成 · 会结合当前会话上下文|SearchCheck|Maximize2|Minimize2|isInputExpanded|setIsInputExpanded" src/components/chat-page src/components/chat src/pages/Chat.tsx
rg -n "鏈|娣|鍏|璇|灞|鏀|�" src/components/chat-page src/components/chat src/pages/Chat.tsx src/pages/Documents.tsx src/pages/Settings.tsx
```

## 验证结果

- `pnpm exec eslint src/pages/Settings.tsx src/api/auth.ts src/store/auth.ts src/components/settings src/components/chat src/components/chat-page src/components/slider-sidebar.tsx src/components/MainHeader.tsx src/components/user-avatar.tsx` 已通过。
- `pnpm build` 已通过，仅保留既有 Vite 大 chunk 提示。
- `rg -n "fetch\(" src` 仅匹配 `frontend/src/api/chat-stream.ts` 的 POST SSE 例外。
- 指定 Chat/Settings 范围 mojibake 残留检查无匹配。
- `/Settings` Browser 验收已通过：联系方式可保存和清空，默认头像 preset 可保存，真实 OSS 上传头像刷新后以 `blob:` URL 回显，页面不展示“删除头像”按钮，DOM/text/console 未发现 OSS object key、AccessKey、Secret、Authorization、OSS 鉴权查询参数或永久 URL。
- 本轮目标 ESLint、`pnpm build`、`fetch(` 例外检查、旧 Chat 文案/旧放大按钮状态检查和 Chat/Documents/Settings mojibake 检查已通过。
- 本轮响应式兼容目标 ESLint 已通过：`pnpm exec eslint src/pages/Chat.tsx src/components/chat-page src/components/chat src/pages/Settings.tsx src/components/settings src/components/knowledge-bases`。
- 本轮响应式兼容 `pnpm build` 已通过，仅保留既有 Vite 大 chunk 提示。
- 指定 Settings 描述残留检查无匹配；`fetch(` 仍仅匹配 `frontend/src/api/chat-stream.ts` 的 POST SSE 例外。
- 本轮 Browser 响应式验收覆盖 `375x812`、`360x740`、`768x1024`、`1440x900`：`/KnowledgeBases` 手机端无页面级横向滚动，tabs 仅在自身容器内横向滚动，工具按钮保持触控尺寸，精选卡片首屏可读；桌面端 featured 栅格未退化。
- `/Chat/138` Browser 验收已通过：手机和小手机默认隐藏左会话列表/右引用面板，仅显示聊天主界面；“会话”和“引用来源”可打开底部 Sheet；mention picker 在 360px 视口内不溢出，选择文档后能插入 `@文档名`；平板继续使用移动端 Sheet 结构，桌面端恢复三栏结构。
- `/Settings` Browser 验收已通过：账号资料卡片不再出现指定描述，仍保留“账号资料”“退出登录”“编辑资料”和保存模型/RAG 配置入口，页面不新增“删除头像”入口。

## 手动验收路径

- `/Settings`：资料卡片只读并显示联系方式；点击“编辑资料”后弹窗可修改用户名、邮箱、联系方式，可选择默认头像或上传头像，保存后 Header/Sidebar/Settings 同步更新。
- `/Settings`：OSS 未配置时，本地头像上传入口禁用，并显示“头像上传需要先配置 OSS；当前可选择默认头像”。
- `/Settings`：OSS 已配置时，本地头像可上传到 OSS，刷新后通过后端鉴权头像内容代理重新获取 blob 回显。
- `/Settings`：不再出现“删除头像”按钮。
- `/Chat/{sessionId}`：发送消息时显示“正在思考”。
- `/Chat/{sessionId}`：普通正文中的 `@` 不弹索引；行首或空格后的 `@` 弹文档列表。
- mention 列表支持高亮、键盘选择、分页、删除 `@` 后关闭。
- 会话未读时点击沟通区域变已读，且不会连续重复 PATCH。
- `/Chat/{sessionId}`：流式输出期间向上滚动不会被强制拉回底部；点击“回到底部”或发送新问题后才恢复跟随。
- `/Chat/{sessionId}`：输入框可拖动调整高度，旧右侧放大按钮不存在，顶部说明文案不存在。
- `/Documents/{knowledgeBaseId}`：同一知识库内选择同名同后缀文件时前端 toast 提示并阻止重复项上传。
- `/Settings`：保存配置后不重新填写 API Key 也能测试已保存模型；失败信息脱敏。
- 小屏下 Settings、Chat、mention 列表、引用来源不横向溢出、不互相遮挡。
- `/KnowledgeBases` 手机端：tabs 横向滚动，工具栏不和“精选知识库”互相挤压；精选卡片首屏可读且无横向滚动。
- `/Chat/{sessionId}` 手机端：默认只展示聊天主界面；“会话”和“引用来源”按钮分别打开 Sheet，消息区没有内部横向滚动条。
