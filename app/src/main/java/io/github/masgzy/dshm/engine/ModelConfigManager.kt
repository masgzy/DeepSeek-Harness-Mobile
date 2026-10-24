package io.github.masgzy.dshm.engine

import android.content.Context
import io.github.masgzy.dshm.util.AppLog
import io.github.masgzy.dshm.util.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 应用内模型配置中枢：内置供应商 API Key + 自定义（OpenAI 兼容）供应商 + 默认模型。
 *
 * ## 数据流向（对照 dphmoblie/deepseek-harness-android 的凭据分层）
 *
 * dsh（pinned `@deepseek-ai/dsh-credentials-local`）解析模型凭据的顺序：
 * ```
 * 继承的进程环境（只读） > $DSH_HOME/.credentials.yaml > <调用目录>/.env > $DSH_HOME/.env
 * ```
 * 因此本类把 API Key 并写进容器内 `$DSH_HOME/.credentials.yaml` 的 `refs:` 段
 * （dsh 官方凭据文档、官方前端「模型页」写的同一份文件），而不是塞进程环境 ——
 * 避免凭据经 /proc/<pid>/environ 泄露给容器内任意子进程。
 *
 * 自定义供应商与默认模型写进 home 级 patch 层 `$DSH_HOME/cordis.patch.yml`：
 * - `llm-pi-ai` 插件的 `providers` 段声明自定义供应商（apiKeyEnv 引用凭据文档）；
 * - `agent-default-model` 插件声明会话默认模型（provider 可为内置 id 或自定义 id）。
 *
 * ## 本机存储
 *
 * - API Key 明文只存在 AndroidKeyStore 加密的 prefs（[SecretStore]），容器文件是
 *   "同步副本"：重置运行环境后，下次启动引擎会自动重新同步全部已配置项；
 * - 自定义供应商结构、默认模型为普通 JSON prefs（无敏感内容）。
 *
 * ## 写入安全性
 *
 * `.credentials.yaml` 与 `cordis.patch.yml` 都可能被 dsh Web UI 或用户手工编辑过，
 * 同步采用"保行合并"：只替换本类管辖的段/条目，其余行（OAuth `records`、
 * 注释、用户自加 patch）逐行原样保留。
 */
class ModelConfigManager(private val context: Context) {

  // ---- 内置供应商（与 dsh llm-pi-ai 已知供应商表对齐） ----

  /**
   * @param id dsh 供应商 id（agent-default-model 与会话模型选择使用）
   * @param envName 凭据文档 refs 键 / 环境变量名
   * @param baseUrl 官方 API 根地址（连通性测试用）
   */
  data class BuiltinProvider(
    val id: String,
    val label: String,
    val envName: String,
    val baseUrl: String,
    val apiStyle: ApiStyle,
  )

  enum class ApiStyle { OPENAI, ANTHROPIC, GOOGLE }

  companion object {
    val BUILTINS = listOf(
      BuiltinProvider("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", "https://api.deepseek.com", ApiStyle.OPENAI),
      BuiltinProvider("openai", "OpenAI", "OPENAI_API_KEY", "https://api.openai.com/v1", ApiStyle.OPENAI),
      BuiltinProvider("anthropic", "Anthropic", "ANTHROPIC_API_KEY", "https://api.anthropic.com", ApiStyle.ANTHROPIC),
      BuiltinProvider("google", "Google Gemini", "GEMINI_API_KEY", "https://generativelanguage.googleapis.com/v1beta", ApiStyle.GOOGLE),
      BuiltinProvider("openrouter", "OpenRouter", "OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", ApiStyle.OPENAI),
      BuiltinProvider("groq", "Groq", "GROQ_API_KEY", "https://api.groq.com/openai/v1", ApiStyle.OPENAI),
      BuiltinProvider("xai", "xAI", "XAI_API_KEY", "https://api.x.ai/v1", ApiStyle.OPENAI),
      BuiltinProvider("mistral", "Mistral", "MISTRAL_API_KEY", "https://api.mistral.ai", ApiStyle.OPENAI),
    )

    /** 自定义供应商 api 字段的合法取值（dsh schema const 枚举）。 */
    val CUSTOM_APIS = listOf("openai-completions", "openai-responses", "anthropic-messages")

    private const val MAX_CUSTOM_PROVIDERS = 16
    private const val MAX_CUSTOM_MODELS = 32

    const val CREDENTIALS_FILE = ".credentials.yaml"
    const val PATCH_FILE = "cordis.patch.yml"
    /** 本类管辖的两个 patch 条目 id；同步时其它条目原样保留。 */
    private val MANAGED_PATCH_IDS = setOf("llm-pi-ai", "agent-default-model")

    fun customEnvName(providerId: String): String =
      "DSH_CUSTOM_PROVIDER_${providerId.uppercase().replace('-', '_')}_API_KEY"
  }

  /** 自定义供应商标识：小写字母/数字/短横线，字母开头，2-32 位（可作 YAML 键与 env 变量段）。 */
  private val idPattern = Regex("^[a-z][a-z0-9-]{1,31}$")

  private val baseUrlPattern = Regex("^https://[A-Za-z0-9.\\-:_/]+$")

  /** 模型 ID：字母/数字/点/短横线/斜杠/下划线（对齐上游常见命名）。 */
  private val modelIdPattern = Regex("^[A-Za-z0-9._/-]{1,128}$")

  // ---- 数据模型 ----

  data class CustomModel(
    val id: String,
    val name: String,
    val contextWindow: Int = 65536,
    val maxTokens: Int = 8192,
  )

  data class CustomProvider(
    val id: String,
    val name: String,
    val api: String,
    val baseUrl: String,
    val models: List<CustomModel>,
  ) {
    val envName: String get() = customEnvName(id)
  }

  /** 全量配置快照（UI 编辑的载体）。 */
  data class Config(
    val builtinKeys: Map<String, String> = emptyMap(),   // providerId -> apiKey
    val customProviders: List<CustomProvider> = emptyList(),
    val customKeys: Map<String, String> = emptyMap(),    // customProviderId -> apiKey
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
  )

  // ---- 读写 ----

  private val plainPrefs
    get() = context.getSharedPreferences("dshm", Context.MODE_PRIVATE)

  /** 读取全部配置（Key 从加密存储解出；不落日志）。 */
  fun load(): Config {
    val custom = parseCustomProviders(plainPrefs.getString("model.customProviders", null))
    val customKeys = custom.associate { it.id to (SecretStore.get(context, it.envName) ?: "") }
      .filterValues { it.isNotEmpty() }
    val builtinKeys = BUILTINS.mapNotNull { p ->
      SecretStore.get(context, p.envName)?.let { p.id to it }
    }.toMap()
    return Config(
      builtinKeys = builtinKeys,
      customProviders = custom,
      customKeys = customKeys,
      defaultProvider = plainPrefs.getString("model.defaultProvider", null)?.takeIf { it.isNotBlank() },
      defaultModel = plainPrefs.getString("model.defaultModel", null)?.takeIf { it.isNotBlank() },
    )
  }

  /** 保存全量配置并同步进容器（IO 调用方自行切后台线程）。 */
  fun save(config: Config) {
    // 1) 本机：Key 进加密存储；移除项同步删除
    BUILTINS.forEach { p ->
      val key = config.builtinKeys[p.id]
      if (key.isNullOrBlank()) SecretStore.remove(context, p.envName)
      else SecretStore.put(context, p.envName, key)
    }
    config.customProviders.forEach { p ->
      val key = config.customKeys[p.id]
      if (key.isNullOrBlank()) SecretStore.remove(context, p.envName)
      else SecretStore.put(context, p.envName, key)
    }
    plainPrefs.edit()
      .putString("model.customProviders", dumpCustomProviders(config.customProviders))
      .putString("model.defaultProvider", config.defaultProvider?.takeIf { it.isNotBlank() })
      .putString("model.defaultModel", config.defaultModel?.takeIf { it.isNotBlank() })
      .apply()
    // 2) 容器：并写凭据文档与 patch 层
    sync(config)
  }

  // ---- 自定义供应商序列化（org.json，无新依赖） ----

  fun dumpCustomProviders(providers: List<CustomProvider>): String {
    val arr = JSONArray()
    providers.forEach { p ->
      val models = JSONArray()
      p.models.forEach { m ->
        models.put(
          JSONObject()
            .put("id", m.id)
            .put("name", m.name)
            .put("contextWindow", m.contextWindow)
            .put("maxTokens", m.maxTokens),
        )
      }
      arr.put(
        JSONObject()
          .put("id", p.id)
          .put("name", p.name)
          .put("api", p.api)
          .put("baseUrl", p.baseUrl)
          .put("models", models),
      )
    }
    return arr.toString()
  }

  fun parseCustomProviders(json: String?): List<CustomProvider> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
      val arr = JSONArray(json)
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val modelsArr = o.optJSONArray("models")
        val models = if (modelsArr == null) emptyList() else (0 until modelsArr.length()).mapNotNull { j ->
          val m = modelsArr.optJSONObject(j) ?: return@mapNotNull null
          CustomModel(
            id = m.optString("id"),
            name = m.optString("name", m.optString("id")),
            contextWindow = m.optInt("contextWindow", 65536),
            maxTokens = m.optInt("maxTokens", 8192),
          )
        }
        CustomProvider(
          id = o.optString("id"),
          name = o.optString("name", o.optString("id")),
          api = o.optString("api", "openai-completions"),
          baseUrl = o.optString("baseUrl"),
          models = models,
        )
      }
    } catch (t: Throwable) {
      AppLog.log("model", "自定义供应商配置解析失败，按空处理", t)
      emptyList()
    }
  }

  // ---- 校验（错误抛 IllegalArgumentException，消息直接可展示） ----

  fun requireValid(config: Config) {
    val ids = HashSet<String>()
    config.customProviders.forEach { p ->
      require(idPattern.matches(p.id)) {
        "自定义供应商标识「${p.id}」无效（小写字母/数字/短横线，2-32 位）"
      }
      require(ids.add(p.id)) { "自定义供应商标识「${p.id}」重复" }
      require(p.name.isNotBlank() && p.name.length <= 80) { "供应商「${p.id}」名称为空或超长" }
      require(p.api in CUSTOM_APIS) { "供应商「${p.id}」接口类型无效" }
      require(baseUrlPattern.matches(p.baseUrl.trim())) {
        "供应商「${p.id}」Base URL 必须是 https:// 地址"
      }
      require(p.models.isNotEmpty()) { "供应商「${p.id}」至少需要 1 个模型" }
      require(p.models.size <= MAX_CUSTOM_MODELS) { "供应商「${p.id}」模型数超过 $MAX_CUSTOM_MODELS" }
      val mids = HashSet<String>()
      p.models.forEach { m ->
        require(modelIdPattern.matches(m.id)) { "模型 ID「${m.id}」无效" }
        require(mids.add(m.id)) { "供应商「${p.id}」模型 ID「${m.id}」重复" }
        require(m.contextWindow in 1024..10_000_000) { "模型「${m.id}」上下文长度需在 1024-10000000 之间" }
        require(m.maxTokens in 1..m.contextWindow) { "模型「${m.id}」最大输出不能超过上下文长度" }
      }
    }
    require(config.customProviders.size <= MAX_CUSTOM_PROVIDERS) {
      "自定义供应商最多 $MAX_CUSTOM_PROVIDERS 个"
    }
    val dp = config.defaultProvider
    if (!dp.isNullOrBlank()) {
      val known = BUILTINS.any { it.id == dp } || config.customProviders.any { it.id == dp }
      require(known) { "默认模型的供应商「$dp」未在已配置列表中" }
      require(!config.defaultModel.isNullOrBlank()) { "设置了默认模型供应商时必须填写模型 ID" }
    }
  }

  // ---- 容器同步 ----

  val rootfsDir: File get() = Paths.rootfs(context.filesDir)

  /** 容器内 $DSH_HOME（root/.dsh）对应的宿主目录。 */
  fun dshHomeDir(): File = File(rootfsDir, "root/.dsh")

  /**
   * 把当前配置同步进容器。rootfs 未就绪时静默跳过（首次安装向导里改配置属正常路径），
   * 引擎每次启动前也会调用本方法兜底。
   */
  fun sync(config: Config = load()) {
    val home = dshHomeDir()
    if (!Paths.rootfsUsable(rootfsDir)) return
    try {
      home.mkdirs()
      writeCredentials(home, config)
      writePatch(home, config)
      AppLog.log(
        "model",
        "模型配置已同步：内置 Key ${config.builtinKeys.size} 个，自定义供应商 ${config.customProviders.size} 个" +
          (config.defaultProvider?.let { "，默认模型 $it/${config.defaultModel}" } ?: ""),
      )
    } catch (t: Throwable) {
      AppLog.log("model", "模型配置同步失败", t)
    }
  }

  /**
   * 并写 `.credentials.yaml` 的 `refs:` 段。
   *
   * 本类管辖的键（内置 8 家 + 自定义供应商）以当前值为准写入；文档里其余 refs
   * （如用户在 dsh Web UI 里加的键）与所有其它顶层段（version/records/注释）逐行保留。
   */
  private fun writeCredentials(home: File, config: Config) {
    val managed = LinkedHashMap<String, String>()
    BUILTINS.forEach { p -> config.builtinKeys[p.id]?.takeIf { it.isNotBlank() }?.let { managed[p.envName] = it } }
    config.customProviders.forEach { p ->
      config.customKeys[p.id]?.takeIf { it.isNotBlank() }?.let { managed[p.envName] = it }
    }
    val file = File(home, CREDENTIALS_FILE)
    val existing = if (file.isFile) file.readText() else ""
    val lines = existing.lines().toMutableList()
    if (lines == listOf("")) lines.clear()

    // 定位既有 refs 段（顶层 "refs:" 及其缩进条目）
    val refsIdx = lines.indexOfFirst { Regex("^refs:\\s*$").matches(it) }
    if (refsIdx >= 0) {
      var end = refsIdx + 1
      while (end < lines.size && !TOP_LEVEL.matches(lines[end])) end++
      // 收集既有非托管 refs，避免抹掉 Web UI 侧添加的键
      val kept = LinkedHashMap<String, String>()
      for (i in refsIdx + 1 until end) {
        val m = REF_ENTRY.matchEntire(lines[i])
        if (m != null && m.groupValues[1] !in managed) kept[m.groupValues[1]] = m.groupValues[2]
      }
      val block = buildRefBlock(managed, kept)
      repeat(end - refsIdx - 1) { lines.removeAt(refsIdx + 1) }
      lines.addAll(refsIdx + 1, block)
    } else {
      val block = buildRefBlock(managed, emptyMap())
      // 无 refs 段：插到 records: 之前（保持凭据文档惯用顺序），否则追加到末尾
      val recIdx = lines.indexOfFirst { Regex("^records:").matches(it) }
      if (recIdx >= 0) lines.addAll(recIdx, block) else lines.addAll(block)
    }
    // dsh 凭据文档必须是 version: 1 的映射；既有文档缺 version 时补在首行
    if (lines.none { Regex("^version:").containsMatchIn(it) }) {
      lines.add(0, "version: 1")
    }
    atomicWrite(file, lines.joinToString("\n").let { if (it.endsWith("\n")) it else "$it\n" })
  }

  private fun buildRefBlock(managed: Map<String, String>, kept: Map<String, String>): List<String> {
    if (managed.isEmpty() && kept.isEmpty()) return emptyList()
    val out = mutableListOf("refs:")
    (kept + managed).forEach { (k, v) -> out.add("  ${k}: ${yamlScalar(v)}") }
    return out
  }

  /**
   * 并写 home 级 `cordis.patch.yml`：移除本类管辖的两条旧条目，
   * 再按当前配置追加；其余条目（用户手写 patch）原样保留。
   */
  private fun writePatch(home: File, config: Config) {
    val file = File(home, PATCH_FILE)
    val existing = if (file.isFile) file.readText() else ""
    val otherEntries = splitPatchEntries(existing)
      .filter { entry -> entry.none { line -> Regex("^- id:\\s*(.+)\\s*$").matchEntire(line)?.groupValues?.get(1)?.trim() in MANAGED_PATCH_IDS } }

    val ours = mutableListOf<List<String>>()
    if (config.customProviders.isNotEmpty()) {
      val lines = mutableListOf("- id: llm-pi-ai", "  config:", "    providers:")
      config.customProviders.forEach { p ->
        lines.add("      ${yamlKey(p.id)}:")
        lines.add("        displayName: ${yamlScalar(p.name)}")
        lines.add("        api: ${p.api}")
        lines.add("        baseURL: ${yamlScalar(p.baseUrl.trim())}")
        lines.add("        apiKeyEnv: ${p.envName}")
        lines.add("        models:")
        p.models.forEach { m ->
          lines.add("          - id: ${yamlScalar(m.id)}")
          lines.add("            name: ${yamlScalar(m.name)}")
          lines.add("            contextWindow: ${m.contextWindow}")
          lines.add("            maxTokens: ${m.maxTokens}")
        }
      }
      ours.add(lines)
    }
    if (!config.defaultProvider.isNullOrBlank() && !config.defaultModel.isNullOrBlank()) {
      ours.add(
        listOf(
          "- id: agent-default-model",
          "  config:",
          "    provider: ${yamlScalar(config.defaultProvider!!)}",
          "    model: ${yamlScalar(config.defaultModel!!)}",
        ),
      )
    }

    val all = otherEntries + ours
    val text = if (all.isEmpty()) {
      "# dsh home 级 patch 层（由 DeepSeek Harness Mobile 模型配置维护；可手工追加条目）\n[]\n"
    } else {
      "# dsh home 级 patch 层：前段为手工/其它工具条目，后段由 DeepSeek Harness Mobile 模型配置维护\n" +
        all.joinToString("\n") { entry -> entry.joinToString("\n") } + "\n"
    }
    atomicWrite(file, text)
  }

  /** 把 YAML 顶层数组切成条目行组（`- ` 开头起新条目）。无法解析时返回空（宁可覆盖托管段也不写坏文件）。 */
  private fun splitPatchEntries(text: String): List<List<String>> {
    val lines = text.lines()
      .dropLastWhile { it.isBlank() }
      .filterNot { it.trimStart().startsWith("#") }
    if (lines.none { it.trim() == "[]" }) {
      if (lines.none { it.startsWith("- ") }) return emptyList()
    }
    val entries = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    for (line in lines) {
      if (line.startsWith("- ") || line.trim() == "[]") {
        if (current.isNotEmpty()) entries.add(current)
        current = if (line.trim() == "[]") mutableListOf() else mutableListOf(line)
      } else if (current.isNotEmpty()) {
        current.add(line)
      }
    }
    if (current.isNotEmpty()) entries.add(current)
    return entries
  }

  private fun atomicWrite(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".dshm-tmp")
    tmp.writeText(text)
    tmp.setReadable(false, false)
    tmp.setReadable(true, true)
    if (!tmp.renameTo(file)) {
      // rename 跨场景失败（极少）：退化为直接写
      file.writeText(text)
      tmp.delete()
    }
  }

  // ---- YAML 标量输出辅助 ----

  private val TOP_LEVEL = Regex("^[A-Za-z_][A-Za-z0-9_]*:.*$")
  private val REF_ENTRY = Regex("^ +([A-Za-z_][A-Za-z0-9_]*):\\s*(.*)$")

  /** 键名（供应商标识已校验为 [a-z0-9-]，无需引号）。 */
  private fun yamlKey(k: String): String = k

  /** 值统一走双引号标量，规避 YAML 指示符/前后空格歧义。 */
  private fun yamlScalar(v: String): String = "\"" + v
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "") + "\""
}
