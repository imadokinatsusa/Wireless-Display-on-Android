#!/usr/bin/env node
/**
 * 下载本机构建/验证所需的工具链到 .toolchain/（不进版本库）。
 *
 * 为什么需要：本机没有 JDK / Android SDK / Gradle，导致纯 JVM 逻辑也只能靠
 * CI 验证，一轮一分钟、还只能靠猜。装上 JDK + Gradle 后，
 * `core:*` 的测试就能在本地秒级跑完。
 *
 * 下载源说明：Adoptium 与 services.gradle.org 都会重定向到 github.com，
 * 而本机直连 github.com 会超时（api.github.com 反而通）。因此优先走国内镜像，
 * 任一镜像失败自动换下一个。
 *
 * 用法：node tools/fetch-toolchain.mjs
 */

import { createWriteStream, existsSync, mkdirSync, renameSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const toolchain = join(here, "..", ".toolchain");

const JDK_URLS = [
  "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip",
  "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip",
  "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse",
];

const GRADLE_URLS = [
  "https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip",
  "https://mirrors.aliyun.com/gradle/gradle-8.11.1-bin.zip",
  "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip",
];

const FETCH_TIMEOUT_MS = 120_000;
const MAX_ATTEMPTS = 3;

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
      console.warn(`[toolchain] ${label} 第 ${attempt}/${MAX_ATTEMPTS} 次失败（${cause}）`);
      await new Promise((resolve) => setTimeout(resolve, 2_000 * attempt));
    }
  }
  throw lastError;
}

async function download(urls, dest, label) {
  if (existsSync(dest)) {
    console.log(`[toolchain] ${label} 已存在，跳过（${(statSync(dest).size / 1048576).toFixed(1)} MB）`);
    return;
  }

  let lastError;
  for (const url of urls) {
    console.log(`[toolchain] 下载 ${label}：${url}`);
    try {
      const res = await fetchWithRetry(url, label);
      if (!res.ok) {
        console.warn(`[toolchain] ${label} HTTP ${res.status}，换下一个源`);
        continue;
      }

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
      return;
    } catch (error) {
      lastError = error;
      console.warn(`[toolchain] ${label} 该源失败：${error.cause?.code ?? error.message}`);
    }
  }
  throw lastError ?? new Error(`${label}: 所有下载源都失败`);
}

mkdirSync(toolchain, { recursive: true });
await download(JDK_URLS, join(toolchain, "jdk17.zip"), "JDK 17");
await download(GRADLE_URLS, join(toolchain, "gradle-8.11.1-bin.zip"), "Gradle 8.11.1");
console.log("[toolchain] TOOLCHAIN_DOWNLOAD_OK");
