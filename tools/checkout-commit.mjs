// 把工作区恢复到 GitHub 上的某个提交。
//
// 为什么需要它：本地工作区**没有 git**，所有版本历史都在 GitHub 上
// （push 脚本每次都是一个完整提交）。要回退、要对比、要捡回旧实现，都得靠它。
//
// 用法：
//   node tools/checkout-commit.mjs --repo owner/name --commit <sha> [--token <pat>] [--dry-run]
//
// 行为：把该提交里存在的文件写回本地（覆盖同名文件），**不删除**本地多余文件
// （安全取向：宁可留下多余文件，也不要误删你正在写的东西）。
//
// token 走 --token 或 GITHUB_TOKEN 环境变量；公开仓库不带 token 也能用
// （但会受 60 次/小时的匿名限流，文件多的时候容易撞上）。
import { writeFile, mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";

const EXCLUDED_DIRS = new Set(["build", ".toolchain", ".scratch", "node_modules", ".gradle", ".kotlin", ".git"]);

function parseArgs(argv) {
  const args = { dryRun: false };
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    switch (token) {
      case "--repo": args.repo = argv[++i]; break;
      case "--commit": args.commit = argv[++i]; break;
      case "--token": args.token = argv[++i]; break;
      case "--dir": args.dir = argv[++i]; break;
      case "--dry-run": args.dryRun = true; break;
      default:
        if (token.startsWith("--")) { console.error(`未知参数：${token}`); process.exit(64); }
    }
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
const token = args.token ?? process.env.GITHUB_TOKEN;
if (!args.repo || !args.commit) {
  console.error("用法：node tools/checkout-commit.mjs --repo owner/name --commit <sha> [--dry-run]");
  process.exit(64);
}
const root = args.dir ?? process.cwd();
const headers = { Accept: "application/vnd.github+json", "User-Agent": "dsh-agent" };
if (token) headers.Authorization = `Bearer ${token}`;

async function api(path) {
  const response = await fetch(`https://api.github.com/repos/${args.repo}${path}`, { headers });
  if (!response.ok) throw new Error(`${path} -> HTTP ${response.status}`);
  return response.json();
}

function isExcluded(path) {
  const segments = path.split("/");
  if (segments.some((segment) => EXCLUDED_DIRS.has(segment))) return true;
  return segments[segments.length - 1] === "local.properties";
}

const commit = await api(`/commits/${args.commit}`);
const tree = await api(`/git/trees/${commit.commit.tree.sha}?recursive=1`);
const blobs = (tree.tree ?? []).filter((entry) => entry.type === "blob" && !isExcluded(entry.path));

console.log(`提交 ${commit.sha.slice(0, 8)}：${commit.commit.message.split("\n")[0]}`);
console.log(`文件 ${blobs.length} 个${args.dryRun ? "（dry-run，不写盘）" : ""}`);

let restored = 0;
const concurrency = 6;
for (let index = 0; index < blobs.length; index += concurrency) {
  const batch = blobs.slice(index, index + concurrency);
  await Promise.all(
    batch.map(async (entry) => {
      if (args.dryRun) {
        console.log(`  would restore ${entry.path}`);
        return;
      }
      const blob = await api(`/git/blobs/${entry.sha}`);
      const content = Buffer.from(blob.content, blob.encoding === "base64" ? "base64" : "utf8");
      const target = join(root, entry.path);
      await mkdir(dirname(target), { recursive: true });
      await writeFile(target, content);
      restored += 1;
    }),
  );
  if (!args.dryRun) process.stdout.write(`\r已恢复 ${restored}/${blobs.length}`);
}
if (!args.dryRun) console.log();
console.log(args.dryRun ? "dry-run 结束" : `✓ 已恢复到 ${commit.sha.slice(0, 8)}`);
if (token) console.log("提示：用完请撤销 token。");
