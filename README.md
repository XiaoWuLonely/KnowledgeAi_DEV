# KnowFlow AI

## 简介

KnowFlow AI 是一个基于 RAG 的智能知识库问答平台。系统支持文档上传与解析、知识库检索、Chat 问答、引用来源展示、Settings 模型配置、用户头像、任务中心和移动端适配等能力，适合把学习资料、项目文档、产品资料或团队知识沉淀为可检索、可追问的知识库。

## 技术栈

前端：

- React 19
- TypeScript
- Vite
- Tailwind CSS v4
- React Router
- axios
- Zustand
- shadcn/radix-sera UI
- Lucide icons
- Sonner

后端：

- Java 21
- Spring Boot
- Spring Web MVC
- Spring Security
- MyBatis
- Flyway
- PostgreSQL
- pgvector
- Maven

部署：

- Docker Compose
- Nginx
- `pgvector/pgvector:pg17`

## 项目结构

```text
MyWork/
  frontend/             # React/Vite 前端项目，生产镜像由 Nginx 托管
  backend/              # Spring Boot 后端项目
  doc/                  # 项目阶段、接口和任务文档
  compose.yaml          # 本地开发专用 PostgreSQL
  compose.deploy.yaml   # 完整 Docker 部署：前端 + 后端 + PostgreSQL
  .env.example          # 环境变量模板
```

根目录是协调工作区，不包含 Node 包。前端命令在 `frontend/` 下执行，后端 Maven 命令在 `backend/` 下执行，Docker Compose 命令在仓库根目录执行。

## 准备环境变量

先复制模板：

```powershell
Copy-Item .env.example .env
```

上线或对外部署前必须编辑 `.env`，替换所有 `CHANGE_ME`：

```env
POSTGRES_PASSWORD=CHANGE_ME_postgres_password
KNOWFLOW_JWT_SECRET=CHANGE_ME_use_a_long_random_jwt_secret_at_least_32_chars
KNOWFLOW_MODEL_SECRET_KEY=CHANGE_ME_use_a_long_random_model_secret_at_least_32_chars
```

真实 API Key、OSS 密钥、JWT 密钥、模型加密密钥不要提交到 Git。`.env` 已被 `.gitignore` 忽略，生产环境建议使用部署平台的 Secret/环境变量管理能力。

## 方式一：按源码快速部署

适合本地开发、联调和源码方式运行。

环境要求：

- Node.js 18+ 或项目当前 pnpm 可用的 Node 版本
- pnpm
- Java 21+
- Docker Desktop 或兼容 Docker Compose 的运行环境

1. 启动本地 PostgreSQL：

```powershell
cd MyWork
docker compose up -d
```

根目录 `compose.yaml` 只负责启动本地 PostgreSQL，不会启动前端和后端。

2. 启动后端：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

3. 启动前端：

```powershell
cd frontend
pnpm install
pnpm dev
```

默认访问地址：

- 前端：http://localhost:5173
- 后端：http://localhost:8080
- 健康检查：http://localhost:8080/api/health

源码模式下，前端开发服务通过 Vite 代理把 `/api/**` 转发到 `http://localhost:8080/api/**`。

## 方式二：Docker 快速部署

适合演示环境、测试环境或单机部署。该方式使用新增的 `compose.deploy.yaml`，一次启动 PostgreSQL、后端和前端。

1. 准备 `.env` 并替换密钥：

```powershell
cd MyWork
Copy-Item .env.example .env
```

2. 构建并启动完整应用：

```powershell
docker compose -f compose.deploy.yaml up -d --build
```

3. 查看容器状态：

```powershell
docker compose -f compose.deploy.yaml ps
```

4. 访问应用：

```text
http://localhost:${KNOWFLOW_FRONTEND_PORT:-5173}
```

默认情况下：

- 前端 Nginx 映射到 `http://localhost:5173`
- 后端 Spring Boot 映射到 `http://localhost:8080`
- PostgreSQL 映射到 `localhost:5432`

Docker 部署链路：

```text
Browser -> frontend(Nginx) -> backend(Spring Boot) -> postgres(pgvector)
```

前端 Nginx 会把 `/api` 代理到容器网络内的 `http://backend:8080/api`。后端通过 `KNOWFLOW_DB_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB}` 连接容器内 PostgreSQL。

停止完整部署：

```powershell
docker compose -f compose.deploy.yaml down
```

如果确认部署数据可以删除，再删除数据库卷：

```powershell
docker compose -f compose.deploy.yaml down -v
```

## 常用验证命令

检查后端健康状态：

```powershell
curl http://localhost:8080/api/health
```

检查完整 Docker 部署状态：

```powershell
docker compose -f compose.deploy.yaml ps
```

检查 Docker 部署配置：

```powershell
docker compose -f compose.deploy.yaml config --quiet
```

前端生产构建：

```powershell
cd frontend
pnpm build
```

后端打包：

```powershell
cd backend
.\mvnw.cmd -DskipTests package
```

后端测试：

```powershell
cd backend
.\mvnw.cmd test
```

## 环境变量说明

| 变量 | 默认/示例 | 说明 |
|---|---|---|
| `KNOWFLOW_FRONTEND_PORT` | `5173` | Docker 部署时前端 Nginx 映射到宿主机的端口。 |
| `KNOWFLOW_BACKEND_PORT` | `8080` | Docker 部署时后端 Spring Boot 映射到宿主机的端口。 |
| `KNOWFLOW_POSTGRES_PORT` | `5432` | PostgreSQL 映射到宿主机的端口。 |
| `POSTGRES_DB` | `knowflow` | PostgreSQL 数据库名。 |
| `POSTGRES_USER` | `knowflow` | PostgreSQL 用户名。 |
| `POSTGRES_PASSWORD` | `CHANGE_ME...` | PostgreSQL 密码，生产部署必须替换。 |
| `KNOWFLOW_JWT_SECRET` | `CHANGE_ME...` | JWT 签名密钥，建议至少 32 个随机字符。 |
| `KNOWFLOW_MODEL_SECRET_KEY` | `CHANGE_ME...` | 用户模型 API Key 的后端加密密钥。修改后，历史已保存 API Key 将无法解密。 |
| `KNOWFLOW_AI_BASE_URL` | 空 | 可选 OpenAI-compatible 兜底 Base URL。仅当用户未在 Settings 保存模型配置时使用。 |
| `KNOWFLOW_AI_API_KEY` | 空 | 可选 AI 兜底 API Key，不要提交真实 Key。 |
| `KNOWFLOW_AI_MODEL` | 空 | 可选 AI 兜底模型名。 |
| `KNOWFLOW_AI_EMBEDDING_MODEL` | 空 | 可选 embedding 模型名。为空时保留全文检索，不写入语义向量。 |
| `KNOWFLOW_AI_TIMEOUT_SECONDS` | `60` | 模型调用超时时间。 |
| `KNOWFLOW_OSS_ENDPOINT` | 空 | 可选阿里云 OSS endpoint。为空时头像上传不可用，但默认头像仍可用。 |
| `KNOWFLOW_OSS_BUCKET` | 空 | 可选 OSS bucket 名称。 |
| `KNOWFLOW_OSS_ACCESS_KEY_ID` | 空 | 可选 OSS AccessKey ID，不要提交真实密钥。 |
| `KNOWFLOW_OSS_ACCESS_KEY_SECRET` | 空 | 可选 OSS AccessKey Secret，不要提交真实密钥。 |
| `KNOWFLOW_OSS_AVATAR_PREFIX` | `knowflow/avatars` | 头像对象前缀，用于隔离 KnowFlow 上传路径。 |
| `KNOWFLOW_DB_MAX_POOL_SIZE` | `10` | Docker 后端数据库连接池最大连接数。 |
| `KNOWFLOW_DB_MIN_IDLE` | `1` | Docker 后端数据库连接池最小空闲连接数。 |

## 常见问题排查

数据库端口冲突：

- 如果本机已有 PostgreSQL 占用 `5432`，修改 `.env` 中的 `KNOWFLOW_POSTGRES_PORT`，例如 `15432`。
- 修改后重新运行 `docker compose -f compose.deploy.yaml up -d --build`。

`.env` 未配置或仍是 `CHANGE_ME`：

- Docker 部署要求 `POSTGRES_PASSWORD`、`KNOWFLOW_JWT_SECRET` 和 `KNOWFLOW_MODEL_SECRET_KEY` 可用。
- 生产部署必须使用足够长的随机值，不要使用模板占位值。

旧数据卷密码未更新：

- PostgreSQL 初始化后，数据卷里的密码不会因为 `.env` 改动而自动更新。
- 演示环境可以在确认数据可删除后执行 `docker compose -f compose.deploy.yaml down -v`，再重新启动。
- 生产环境应先备份数据，再按 PostgreSQL 正规流程修改密码。

Chat 没有真实模型输出：

- 先在 `/Settings` 保存并测试真实 OpenAI-compatible Base URL、API Key 和模型名。
- 如果只配置了兜底模型变量，而用户在 Settings 保存了自己的配置，系统会优先使用用户配置。
- 没有配置 `KNOWFLOW_AI_EMBEDDING_MODEL` 时，系统仍可使用全文检索，但不会生成语义向量。

头像上传不可用：

- 检查 `KNOWFLOW_OSS_ENDPOINT`、`KNOWFLOW_OSS_BUCKET`、`KNOWFLOW_OSS_ACCESS_KEY_ID`、`KNOWFLOW_OSS_ACCESS_KEY_SECRET` 是否配置。
- OSS 未配置时，用户仍可选择默认头像。

前端无法访问后端：

- Docker 部署下检查 `frontend/nginx.conf` 是否仍代理 `/api` 到 `backend:8080`。
- 源码模式下检查后端是否已在 `backend/` 目录通过 `.\mvnw.cmd spring-boot:run` 启动。
- 使用 `curl http://localhost:8080/api/health` 先确认后端健康状态。

## 生产上线提醒

- 不要把 `.env`、真实 API Key、OSS 密钥或部署日志提交到 Git。
- 首次部署前确认 `KNOWFLOW_JWT_SECRET` 和 `KNOWFLOW_MODEL_SECRET_KEY` 已替换为生产密钥。
- 如果已经有用户保存了模型 API Key，不要随意更换 `KNOWFLOW_MODEL_SECRET_KEY`。
- 推荐给 PostgreSQL 数据卷建立备份策略。
- 对外暴露服务时，建议在前端 Nginx 或外层网关前接入 HTTPS。

## 主要文档

- [项目概览](doc/PROJECT.md)
- [阶段计划](doc/STAGE_PLAN.md)
- [API 契约](doc/API.md)
- [前端任务记录](doc/FRONTEND_TASK.md)
- [后端任务记录](doc/BACKEND_TASK.md)

## License

MIT License
