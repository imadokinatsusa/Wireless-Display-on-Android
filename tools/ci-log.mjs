#!/usr/bin/env node
/**
 * 拉取 GitHub Actions 最近一次运行的状态与关键日志，用于快速定位编译/测试失败。
 *
 * 用法：
 *   GITHUB_TOKEN=xxx node tools/ci-log.mjs --repo <owner>/<name> [--run <id>] [--full]
 *
 * token 权限：Contents: read（公开仓库匿名也能读状态，但读日志需要认证）
 */

const API = "https://api.github.com";

const KEY_LINE = new RegExp(
  [
    "e: file://",
    "w: file://.*(warning|deprecat)",
    "error:",
    "Unresolved reference",
    "Compilation error",
    "FAILED",
    "Execution failed",
    "What went wrong",
    "Caused by:",
    "BUILD FAILED",
    "Could not resolve",
    "Could not find",
    "Could not determine",
    "Plugin \\[id",
    "> Task .* FAILED",
  ].join("|"),
);

function parseArgs(argv) {
  const args = { full: false };
  for (let index = 0; index < argv.length; index++) {
    switch (argv[index]) {
      case "--repo":
        args.repo = argv[++index];
        break;
      case "--run":
        args.run = argv[++index];
        break;
      case "--full":
        args.full = true;
        break;
      case "--key-only":
        args.keyOnly = true;
        break;
      default:
        if (argv[index].startsWith("--")) {
          console.error(`未知参数：${argv[index]}`);
          process.exit(1);
        }
    }
  }
  return args;
}

async function api(path, token) {
  const res = await fetch(`${API}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "whalecast-ci-log",
    },
  });
  if (!res.ok) {
    throw new Error(`GET ${path} -> HTTP ${res.status}: ${await res.text()}`);
  }
  return await res.json();
}

async function fetchJobLog(repo, token, jobId) {
  const res = await fetch(`${API}/repos/${repo}/actions/jobs/${jobId}/logs`, {
    headers: { Authorization: `Bearer ${token}`, "User-Agent": "whalecast-ci-log" },
    redirect: "follow",
  });
  if (!res.ok) return `（无法获取日志：HTTP ${res.status}）`;
  const buffer = Buffer.from(await res.arrayBuffer());
  // 有时返回的是 zip 归档；做一次宽松判断，避免把二进制 garbage 打到屏幕上。
  if (buffer[0] === 0x50 && buffer[1] === 0x4b) {
    return "（日志为 zip 归档，跳过解析。可到 Actions 页面下载完整日志。）";
  }
  return buffer.toString("utf8");
}

const args = parseArgs(process.argv.slice(2));
const token = process.env.GITHUB_TOKEN ?? args.token;
if (!token) {
  console.error("缺少 token：设置 GITHUB_TOKEN");
  process.exit(1);
}
if (!args.repo || !args.repo.includes("/")) {
  console.error("缺少仓库：--repo owner/name");
  process.exit(1);
}

const runs = await api(`/repos/${args.repo}/actions/runs?per_page=10`, token);
if (!runs.workflow_runs?.length) {
  console.log("还没有任何运行记录。");
  process.exit(0);
}
const run = args.run
  ? runs.workflow_runs.find((candidate) => String(candidate.id) === String(args.run))
  : runs.workflow_runs[0];

if (!run) {
  console.log("找不到指定的运行记录。");
  process.exit(0);
}

console.log(`运行 #${run.run_number}：${run.status} / ${run.conclusion ?? "-"}`);
console.log(`分支：${run.head_branch}　提交：${run.head_sha?.slice(0, 7)}`);
console.log(run.html_url);

const jobs = await api(`/repos/${args.repo}/actions/runs/${run.id}/jobs`, token);
for (const job of jobs.jobs) {
  console.log(`\n── job：${job.name} → ${job.status} / ${job.conclusion ?? "-"}`);
  for (const step of job.steps ?? []) {
    const conclusion = step.conclusion ?? step.status;
    const mark = conclusion === "success" || conclusion === "skipped" ? " " : "!";
    console.log(`  ${mark} ${step.number}. ${step.name}：${conclusion}`);
  }
}

const failed = jobs.jobs.filter((job) => job.conclusion === "failure");
if (!failed.length) {
  console.log("\n没有失败的 job。");
  process.exit(0);
}

for (const job of failed) {
  const text = await fetchJobLog(args.repo, token, job.id);
  const lines = text.split(/\r?\n/);
  console.log(`\n======== ${job.name}：关键日志 ========`);
  const key = lines.filter((line) => KEY_LINE.test(line));
  console.log(key.slice(0, args.full ? key.length : 200).join("\n") || "（没有匹配到关键行）");
  if (!args.keyOnly) {
    console.log(`\n======== ${job.name}：末尾 60 行 ========`);
    console.log(lines.slice(args.full ? 0 : -60).join("\n"));
  }
}
