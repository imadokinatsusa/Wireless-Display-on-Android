#!/usr/bin/env node
/**
 * 下载本机构建/验证所需的工具链到 .toolchain/（不进版本库）。
 *
 * 为什么需要：本机没有 JDK / Android SDK / Gradle，导致纯 JVM 逻辑也只能靠
 * CI 验证，一轮一分钟、还只能靠猜。装上 JDK + Gradle 后，
 * `core:*` 的测试就能在本地秒级跑完。
 *
 * 用法：node tools/fetch-toolchain.mjs [--jdk] [--gradle]
 */

import { createWriteStream, existsSync, mkdirSync, renameSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const toolchain = join(here, "..", ".toolchain");

const JDK_URL =
  "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse";
const GRADLE_URL = "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip";

const FETCH_TIMEOUT_MS = 120_000;
const MAX_ATTEMPTS = 6;

/**
 * 下载专用 fetch：直连 GitHub（Adoptium 会重定向过去）在国内网络下会偶发
 * 连接超时，没有重试就整批失败。undici 的 connect timeout 是 10 秒，
 * 靠多次重试把它糊过去即可。
 */
async function fetchWithRetry(url, label) {
  let lastError;
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    try {
      return await fetch(url, {
        redirect: "follow",
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      });
    } catch (error) {
      lastError = error;
      const cause = error.cause?.code ?? error.message;
      const wait = 3_000 * attempt;
      console.warn(
        `[toolchain] ${label} 第 ${attempt}/${MAX_ATTEMPTS} 次失败（${cause}），${wait / 1000}s 后重试…`,
      );
      await new Promise((resolve) => setTimeout(resolve, wait));
    }
  }
  throw lastError;
}

async function download(url, dest, label) {
  if (existsSync(dest)) {
    console.log(`[toolchain] ${label} 已存在，跳过（${(statSync(dest).size / 1048576).toFixed(1)} MB）`);
    return;
  }
  console.log(`[toolchain] 下载 ${label} …`);
  const res = await fetchWithRetry(url, label);
  if (!res.ok) throw new Error(`${label}: HTTP ${res.status}`);

  const total = Number(res.headers.get("content-length") ?? 0);
  let got = 0;
  let lastLog = 0;
  const body = Readable.fromWeb(res.body);
  body.on("data", (chunk) => {
    got += chunk.length;
    const now = Date.now();
    if (now - lastLog > 5_000) {
      lastLog = now;
      const totalText = total ? ` / ${(total / 1048576).toFixed(1)} MB` : "";
      console.log(`[toolchain]   ${label}: ${(got / 1048576).toFixed(1)} MB${totalText}`);
    }
  });

  const part = `${dest}.part`;
  await pipeline(body, createWriteStream(part));
  renameSync(part, dest);
  console.log(`[toolchain] ✓ ${label} 完成：${(statSync(dest).size / 1048576).toFixed(1)} MB`);
}

mkdirSync(toolchain, { recursive: true });
await download(JDK_URL, join(toolchain, "jdk17.zip"), "JDK 17");
await download(GRADLE_URL, join(toolchain, "gradle-8.11.1-bin.zip"), "Gradle 8.11.1");
console.log("[toolchain] TOOLCHAIN_DOWNLOAD_OK");
