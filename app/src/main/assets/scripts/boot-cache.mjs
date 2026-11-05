/**
 * boot-cache.mjs —— proot 容器内 dsh 启动加速预载。
 * 设计移植自 dsh-Go 的 runtime/boot/boot-cache.mjs（AGPL-3.0），路径适配本项目
 * 的容器布局（Alpine apk Node / glibc tarball Node / pnpm hoisted 全局树）。
 *
 * 原理：proot 对 tracee 的每次系统调用做 ptrace 翻译，Node 启动解析模块树时
 * 会发起大量 stat/lstat/exists/readFileSync（package.json 探测、目录扫描），
 * 每次路径查找都是一次陷入。对安装完成后只读的目录树（dsh 全局安装树、Node
 * 运行时树），把首个查询结果缓存进内存，重复查询零陷入；ROOTS 前缀之外的
 * 路径全部原样透传，运行期可写路径不受任何影响。
 *
 * 生命周期：经 NODE_OPTIONS="--import=file:///root/.dshm/boot-cache.mjs" 在
 * dsh 之前注入；stdout/stderr 出现 "?token="（dsh web 凭据已打印，热路径结束）
 * 或 120s 后自动撤除全部补丁（disabled 旗标兜底，持有旧引用的 graceful-fs 等
 * 也回到纯透传）。任何异常静默还原（fail-open）——加速器本身绝不能成为启动
 * 失败源。
 *
 * 只读根（可用环境变量 DSHM_BOOT_CACHE_ROOTS 追加，冒号分隔）：
 *   /usr/local/lib/node_modules       npm -g 全局树（glibc tarball / Alpine 回退）
 *   /usr/lib/node_modules             apk nodejs npm -g 全局树（Alpine）
 *   /root/.local/share/pnpm/global    pnpm -g 全局树（hoisted 平铺布局）
 *   /usr/local/bin                    dsh / pnpm / node shim 所在
 */
const WATCHDOG_MS = 120_000;
const MAX_ENTRIES = 50_000;
const TOKEN_MARK = '?token=';

try {
  // 必须用 createRequire 拿 CJS builtin 对象（而非 await import 命名空间）：
  // require('node:fs') 返回的是全进程共享的同一模块对象，CJS 消费方（graceful-fs
  // 等）与 ESM 的 default/命名导出（首次建 facade 时快照 CJS 属性）全部能吃到
  // 补丁 —— 沙盒 node 24 实测三条导入路径均命中补丁。
  const { createRequire } = await import('node:module');
  const fs = createRequire('/dshm/boot-cache.mjs')('node:fs');

  const ROOTS = [
    '/usr/local/lib/node_modules',
    '/usr/lib/node_modules',
    '/root/.local/share/pnpm/global',
    '/usr/local/bin',
  ];
  for (const extra of (process.env.DSHM_BOOT_CACHE_ROOTS || '').split(':')) {
    if (extra) ROOTS.push(extra);
  }

  const orig = {
    existsSync: fs.existsSync,
    statSync: fs.statSync,
    lstatSync: fs.lstatSync,
    readFileSync: fs.readFileSync,
    readdirSync: fs.readdirSync,
  };

  let disabled = false;
  let hits = 0;
  const existsCache = new Map(); // path -> true/false
  const statCache = new Map(); // key -> stat 结果 | Error(ENOENT)
  const readCache = new Map(); // key -> Buffer|string|Error(ENOENT)
  const readdirCache = new Map(); // key -> entries|Error(ENOENT)

  const inRoots = (p) => {
    if (typeof p !== 'string' || p.length === 0) return false;
    for (const r of ROOTS) {
      if (p === r || p.startsWith(r.endsWith('/') ? r : r + '/')) return true;
    }
    return false;
  };

  // stat/read 的第二个参数（bigint / encoding / throwIfNoEntry）改变返回类型与
  // 语义，必须进缓存键；throwIfNoEntry=false 的「软失败」返回 undefined 不抛错。
  const statKey = (p, o) =>
    p + '\u0000' + (o?.bigint ? 'B' : '-') + (o?.throwIfNoEntry === false ? 'S' : 'H');
  const readKey = (p, o) =>
    p + '\u0000' + (typeof o === 'string' ? o : o?.encoding || '') +
    '\u0000' + (typeof o === 'object' && o && o.flag ? o.flag : '');
  const dirKey = (p, o) => p + '\u0000' + (o?.withFileTypes ? 'D' : '-') + (o?.encoding || '');

  /** 缓存包装：命中直接返回；ENOENT/ENOTDIR 一并缓存（模块解析的候选探测大头）。 */
  const cached = (map, key, soft, compute) => {
    if (map.size >= MAX_ENTRIES) return compute();
    let v = map.get(key);
    if (v === undefined) {
      try {
        v = compute();
      } catch (e) {
        if (e && (e.code === 'ENOENT' || e.code === 'ENOTDIR')) {
          map.set(key, e);
          throw e;
        }
        throw e; // 其他错误不缓存，透传语义
      }
      map.set(key, v);
    } else {
      hits++;
    }
    if (v instanceof Error) throw v;
    return v;
  };

  const softUndefined = (fn) => (...args) => {
    // throwIfNoEntry=false 时 statSync 不抛 ENOENT 而返回 undefined
    return fn(...args);
  };

  fs.existsSync = (p, o) => {
    if (disabled || !inRoots(p)) return orig.existsSync(p, o);
    if (existsCache.size >= MAX_ENTRIES) return orig.existsSync(p, o);
    if (existsCache.has(p)) {
      hits++;
      return existsCache.get(p);
    }
    const v = orig.existsSync(p, o);
    existsCache.set(p, v);
    return v;
  };

  fs.statSync = (p, o) => {
    if (disabled || !inRoots(p)) return orig.statSync(p, o);
    const soft = o?.throwIfNoEntry === false;
    const key = statKey(p, o);
    if (soft) {
      // 软失败语义：ENOENT 缓存为 undefined，非错误
      if (statCache.has(key)) {
        hits++;
        return statCache.get(key);
      }
      const v = orig.statSync(p, o);
      if (statCache.size < MAX_ENTRIES) statCache.set(key, v);
      return v;
    }
    return cached(statCache, key, false, () => orig.statSync(p, o));
  };

  fs.lstatSync = (p, o) => {
    if (disabled || !inRoots(p)) return orig.lstatSync(p, o);
    const soft = o?.throwIfNoEntry === false;
    const key = statKey('\u0001' + p, o); // 前缀区分 stat/lstat（结果可能是 symlink）
    if (soft) {
      if (statCache.has(key)) {
        hits++;
        return statCache.get(key);
      }
      const v = orig.lstatSync(p, o);
      if (statCache.size < MAX_ENTRIES) statCache.set(key, v);
      return v;
    }
    return cached(statCache, key, false, () => orig.lstatSync(p, o));
  };

  fs.readFileSync = (p, o) => {
    if (disabled || !inRoots(p)) return orig.readFileSync(p, o);
    return cached(readCache, readKey(p, o), false, () => orig.readFileSync(p, o));
  };

  fs.readdirSync = (p, o) => {
    if (disabled || !inRoots(p)) return orig.readdirSync(p, o);
    return cached(readdirCache, dirKey(p, o), false, () => orig.readdirSync(p, o));
  };

  function restore(reason) {
    if (disabled) return;
    disabled = true; // 先断缓存：持有旧引用的方（graceful-fs 等）也回到透传
    existsCache.clear();
    statCache.clear();
    readCache.clear();
    readdirCache.clear();
    try {
      Object.assign(fs, orig);
    } catch {
      /* Object.assign 对冻结属性可能失败，disabled 旗标已兜底 */
    }
    if (process.env.DSHM_BOOT_CACHE_DEBUG === '1') {
      try {
        process.stderr.write(`[boot-cache] 已撤除（${reason}），命中 ${hits} 次\n`);
      } catch {
        /* ignore */
      }
    }
  }

  const sniff = (origWrite) => (chunk, ...rest) => {
    try {
      const text = typeof chunk === 'string' ? chunk : Buffer.isBuffer(chunk) ? chunk.toString('latin1') : '';
      if (text.includes(TOKEN_MARK)) restore('token');
    } catch {
      /* ignore */
    }
    return origWrite(chunk, ...rest);
  };
  // write 是流方法，必须 bind this（沙盒冒烟实测：裸引用调用崩溃 _writableState）
  process.stdout.write = sniff(process.stdout.write.bind(process.stdout));
  process.stderr.write = sniff(process.stderr.write.bind(process.stderr));

  // 兜底看门狗：热路径最长 120s（冷启动实测 10-65s），到期无条件撤除
  const watchdog = setTimeout(() => restore('timeout'), WATCHDOG_MS);
  watchdog.unref?.();
} catch {
  /* 预载失败绝不阻断 dsh 启动（fail-open） */
}
