#!/usr/bin/env node
/**
 * 用 GitHub REST API 把当前工程推到一个仓库并触发 Actions 构建。
 * **不需要本机安装 git**（本机确实没有 git，只有 node）。
 *
 * 用法：
 *   node tools/push-to-github.mjs --repo <owner>/<name> --token <PAT> [选项]
 *
 * 选项：
 *   --branch <name>   目标分支；默认用仓库的 default_branch
 *   --message <text>  提交信息
 *   --create          仓库不存在时自动创建（公开仓库）
 *   --no-watch        推送后不等待构建结果
 *
 * token 权限：
 *   classic PAT —— 勾选 repo + workflow（推 .github/workflows 必须）
 *   fine-grained —— Contents: RW、Workflows: RW；若用 --create 还需 Administration: RW
 */

import { readdir, readFile } from "node:fs/promises";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const API = "https://api.github.com";
const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");

const EXCLUDED_DIRS = new Set([
  ".git",
  ".gradle",
  ".idea",
  ".kotlin",
  "build",
  ".toolchain",
  ".scratch",
  "node_modules",
]);
const EXCLUDED_FILES = new Set(["local.properties"]);

function parseArgs(argv) {
  const args = { watch: true };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    switch (token) {
      case "--repo":
        args.repo = argv[++i];
        break;
      case "--token":
        args.token = argv[++i];
        break;
      case "--branch":
        args.branch = argv[++i];
        break;
      case "--message":
        args.message = argv[++i];
        break;
      case "--create":
        args.create = true;
        break;
      case "--no-watch":
        args.watch = false;
        break;
      default:
        if (token.startsWith("--")) fail(`未知参数：${token}`);
    }
  }
  return args;
}

function log(message) {
  console.log(`[push] ${message}`);
}

function fail(message) {
  console.error(`[push] ✗ ${message}`);
  process.exit(1);
}

const FETCH_TIMEOUT_MS = 30_000;
const MAX_ATTEMPTS = 4;

/**
 * fetch + 超时 + 退避重试。
 * 直连 GitHub API 在国内网络下会偶发连接超时，没有重试就会整批推送失败。
 */
async function fetchWithRetry(url, options = {}, label = "请求") {
  let lastError;
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    try {
      return await fetch(url, { ...options, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
    } catch (error) {
      lastError = error;
      const cause = error.cause?.code ?? error.message;
      console.warn(`[push] ${label} 第 ${attempt}/${MAX_ATTEMPTS} 次失败（${cause}），稍后重试…`);
      await new Promise((resolve) => setTimeout(resolve, 1_000 * attempt));
    }
  }
  throw lastError;
}

async function api(path, { method = "GET", body, token, raw = false } = {}) {
  const res = await fetchWithRetry(`${API}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: raw ? "application/vnd.github+json" : "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "whalecast-push",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    redirect: "follow",
  });
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!res.ok) {
    const message = data && data.message ? data.message : text;
    const error = new Error(`${method} ${path} -> HTTP ${res.status}: ${message}`);
    error.status = res.status;
    error.data = data;
    throw error;
  }
  return data;
}

async function collectFiles(dir, out = []) {
  const entries = await readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (EXCLUDED_DIRS.has(entry.name)) continue;
      await collectFiles(full, out);
    } else if (entry.isFile()) {
      if (EXCLUDED_FILES.has(entry.name)) continue;
      if (entry.name.endsWith(".part") || entry.name.endsWith(".apk")) continue;
      out.push(full);
    }
  }
  return out;
}

async function fetchJobLog(owner, name, token, jobId) {
  const res = await fetchWithRetry(
    `${API}/repos/${owner}/${name}/actions/jobs/${jobId}/logs`,
    {
      headers: {
        Authorization: `Bearer ${token}`,
        "User-Agent": "whalecast-push",
      },
      redirect: "follow",
    },
    "获取构建日志",
  );
  if (!res.ok) return `（无法获取日志：HTTP ${res.status}）`;
  return await res.text();
}

async function watchRun(owner, name, token, startedAt) {
  const deadline = Date.now() + 30 * 60 * 1000;
  let lastStatus = "";
  while (Date.now() < deadline) {
    const runs = await api(`/repos/${owner}/${name}/actions/runs?per_page=5`, { token });
    const run = runs.workflow_runs.find(
      (candidate) => new Date(candidate.created_at).getTime() >= startedAt - 15_000,
    );
    if (run) {
      if (run.status !== lastStatus) {
        lastStatus = run.status;
        log(`构建 #${run.run_number}：${run.status}${run.conclusion ? ` / ${run.conclusion}` : ""}`);
        log(`  ${run.html_url}`);
      }
      if (run.status === "completed") return run;
    }
    await new Promise((resolve) => setTimeout(resolve, 20_000));
  }
  log("等待构建超时（30 分钟），可稍后自行查看 Actions 页面。");
  return null;
}

async function reportFailure(owner, name, token, run) {
  const jobs = await api(`/repos/${owner}/${name}/actions/runs/${run.id}/jobs`, { token });
  for (const job of jobs.jobs) {
    if (job.conclusion !== "failure") continue;
    console.error(`[push] ✗ 失败 job：${job.name}`);
    for (const step of job.steps ?? []) {
      if (step.conclusion === "failure") {
        console.error(`[push]   失败步骤：${step.name}`);
      }
    }
    const logText = await fetchJobLog(owner, name, token, job.id);
    const tail = logText.split(/\r?\n/).slice(-120).join("\n");
    console.error(`[push] ---- ${job.name} 日志末尾 ----`);
    console.error(tail);
  }
}

/**
 * 空仓库无法使用 Git Data API：POST /git/blobs 会返回
 * 409 "Git Repository is empty"。所以先用 Contents API 建一个初始提交，
 * blobs/trees/commits 才能工作。
 * 引导文件用仓库本来就有的 .gitignore，不留下多余的占位垃圾。
 */
async function bootstrapIfEmpty(owner, name, token, branch, repoInfo) {
  try {
    await api(`/repos/${owner}/${name}/git/ref/heads/${branch}`, { token });
    return;
  } catch (error) {
    // 空仓库上这个端点返回 409（而不是 404），两者都表示"还没有这个分支"
    if (error.status !== 404 && error.status !== 409) throw error;
  }
  if (repoInfo.size > 0) return;

  const content = await readFile(join(repoRoot, ".gitignore"));
  await api(`/repos/${owner}/${name}/contents/.gitignore`, {
    method: "PUT",
    token,
    body: {
      message: "chore: bootstrap empty repository",
      content: content.toString("base64"),
    },
  });
  log("空仓库已引导：先用 .gitignore 建立初始提交，Git Data API 现在可用");
}

const args = parseArgs(process.argv.slice(2));
const token = args.token ?? process.env.GITHUB_TOKEN;
const repoArg = args.repo ?? process.env.GITHUB_REPOSITORY;

if (!token) fail("缺少 token：用 --token 或设置 GITHUB_TOKEN");
if (!repoArg || !repoArg.includes("/")) fail("缺少仓库：用 --repo owner/name");

const [owner, name] = repoArg.split("/");

const me = await api("/user", { token });
log(`已认证为 ${me.login}`);

let repo;
try {
  repo = await api(`/repos/${owner}/${name}`, { token });
} catch (error) {
  if (error.status === 404 && args.create) {
    repo = await api("/user/repos", {
      method: "POST",
      token,
      body: {
        name,
        private: false,
        auto_init: false,
        description: "WhaleCast —— Android ↔ Android 局域网双向投屏（切片 01 环回 demo）",
      },
    });
    log(`已创建公开仓库 ${repo.full_name}`);
  } else if (error.status === 404) {
    fail(`仓库 ${owner}/${name} 不存在或 token 无权访问。请先在 GitHub 网页创建空仓库（不要勾 README），或加 --create。`);
  } else {
    throw error;
  }
}

const branch = args.branch ?? repo.default_branch ?? "main";
log(`目标：${repo.full_name} @ ${branch}`);

await bootstrapIfEmpty(owner, name, token, branch, repo);

const files = await collectFiles(repoRoot);
log(`待推送文件 ${files.length} 个`);

const blobs = [];
const concurrency = 8;
const startedAt = Date.now();
for (let index = 0; index < files.length; index += concurrency) {
  const batch = files.slice(index, index + concurrency);
  const uploaded = await Promise.all(
    batch.map(async (full) => {
      const content = await readFile(full);
      const path = relative(repoRoot, full).split("\\").join("/");
      const blob = await api(`/repos/${owner}/${name}/git/blobs`, {
        method: "POST",
        token,
        body: { content: content.toString("base64"), encoding: "base64" },
      });
      return { path, sha: blob.sha };
    }),
  );
  blobs.push(...uploaded);
  process.stdout.write(`\r[push] 已上传 ${blobs.length}/${files.length}`);
}
console.log();

let baseTree;
let parents;
try {
  const ref = await api(`/repos/${owner}/${name}/git/ref/heads/${branch}`, { token });
  const commit = await api(`/repos/${owner}/${name}/git/commits/${ref.object.sha}`, { token });
  baseTree = commit.tree.sha;
  parents = [ref.object.sha];
} catch (error) {
  if (error.status !== 404 && error.status !== 409) throw error;
  log("目标分支还是空的，将创建首次提交。");
}

// 组织 tree 条目：新增/更新 + **删除远端多余文件**。
//
// Git Data API 是"增量覆盖"语义：本地删掉的文件不会因为不提交就消失。
// 不显式用 sha: null 删除，旧代码会一直留在远端参与编译 ——
// 这正是"本地测试全绿、CI 却报一堆已删除文件的编译错误"的成因。
const treeEntries = blobs.map((blob) => ({ path: blob.path, mode: "100644", type: "blob", sha: blob.sha }));
if (baseTree) {
  const localPaths = new Set(blobs.map((blob) => blob.path));
  const remote = await api(`/repos/${owner}/${name}/git/trees/${baseTree}?recursive=1`, { token });
  const removed = [];
  for (const entry of remote.tree ?? []) {
    if (entry.type !== "blob" || localPaths.has(entry.path)) continue;
    removed.push(entry.path);
    treeEntries.push({ path: entry.path, mode: entry.mode ?? "100644", type: "blob", sha: null });
  }
  if (removed.length > 0) {
    log(`删除远端多余文件 ${removed.length} 个：`);
    for (const path of removed.slice(0, 15)) log(`  - ${path}`);
    if (removed.length > 15) log(`  … 其余 ${removed.length - 15} 个`);
  }
}

const tree = await api(`/repos/${owner}/${name}/git/trees`, {
  method: "POST",
  token,
  body: {
    ...(baseTree ? { base_tree: baseTree } : {}),
    tree: treeEntries,
  },
});

const commit = await api(`/repos/${owner}/${name}/git/commits`, {
  method: "POST",
  token,
  body: {
    message:
      args.message ??
      "chore: 同步工作区（mirror / WebRTC 路线）",
    tree: tree.sha,
    ...(parents ? { parents } : {}),
  },
});

if (baseTree) {
  await api(`/repos/${owner}/${name}/git/refs/heads/${branch}`, {
    method: "PATCH",
    token,
    body: { sha: commit.sha, force: true },
  });
} else {
  await api(`/repos/${owner}/${name}/git/refs`, {
    method: "POST",
    token,
    body: { ref: `refs/heads/${branch}`, sha: commit.sha },
  });
}

log(`✓ 已提交 ${commit.sha.slice(0, 7)} 到 ${branch}`);
log(`Actions 页面：https://github.com/${owner}/${name}/actions`);

if (args.watch) {
  const run = await watchRun(owner, name, token, startedAt);
  if (!run) process.exit(0);
  if (run.conclusion === "success") {
    log("✓ 构建成功。APK 在 Release 页面（公开仓库可直接下载）：");
    log(`  https://github.com/${owner}/${name}/releases/latest`);
  } else {
    await reportFailure(owner, name, token, run);
    process.exit(2);
  }
}
