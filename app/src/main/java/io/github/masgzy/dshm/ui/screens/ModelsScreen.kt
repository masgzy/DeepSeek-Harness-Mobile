package io.github.masgzy.dshm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.engine.ModelConfigManager
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.components.DshmDialog
import io.github.masgzy.dshm.ui.components.DshmInputField
import io.github.masgzy.dshm.ui.components.InlineAlert
import io.github.masgzy.dshm.ui.components.SectionCard
import io.github.masgzy.dshm.ui.components.SecondaryButton
import io.github.masgzy.dshm.ui.components.Segmented
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import io.github.masgzy.dshm.util.ProviderProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型配置页：对齐 dphmoblie/deepseek-harness-android 的「模型」设置 ——
 * 内置供应商 API Key、自定义（OpenAI 兼容）供应商、默认模型。
 *
 * 保存即同步进容器（$DSH_HOME/.credentials.yaml + home 级 cordis.patch.yml），
 * 引擎下次启动（或运行中重启）后生效。
 */
@Composable
fun ModelsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
  val p = LocalDshmPalette.current
  val scope = rememberCoroutineScope()
  var config by remember { mutableStateOf(viewModel.modelConfig.load()) }
  var notice by remember { mutableStateOf<String?>(null) }
  var noticeDanger by remember { mutableStateOf(false) }

  var keyEditFor by remember { mutableStateOf<ModelConfigManager.BuiltinProvider?>(null) }
  var editingCustom by remember { mutableStateOf<ModelConfigManager.CustomProvider?>(null) }
  var addingCustom by remember { mutableStateOf(false) }
  var defaultDialog by remember { mutableStateOf(false) }

  val persist: (ModelConfigManager.Config, String) -> Unit = { next, okMsg ->
    scope.launch(Dispatchers.IO) {
      try {
        viewModel.modelConfig.requireValid(next)
        viewModel.modelConfig.save(next)
        withContext(Dispatchers.Main) {
          config = next
          notice = okMsg
          noticeDanger = false
        }
      } catch (t: Throwable) {
        withContext(Dispatchers.Main) {
          notice = t.message ?: "保存失败"
          noticeDanger = true
        }
      }
    }
  }

  Column(
    Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp)
      .padding(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 顶栏（子页返回）
    Row(
      Modifier
        .fillMaxWidth()
        .padding(top = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = MaterialTheme.colorScheme.onSurface)
      }
      Text(
        "模型配置",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    if (notice != null) {
      InlineAlert(
        title = if (noticeDanger) "操作失败" else "已保存",
        message = notice!!,
        danger = noticeDanger,
      )
    }

    // —— 内置供应商 API Key ——
    SectionCard("内置供应商") {
      Text(
        "填入 API Key 后即可在 dsh 对话里选用对应模型。Key 加密存于本机，并写进容器凭据文档。",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      ModelConfigManager.BUILTINS.forEach { provider ->
        val configured = config.builtinKeys[provider.id]?.isNotBlank() == true
        Row(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshmRadius.control.dp))
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
            ) { keyEditFor = provider }
            .padding(vertical = 8.dp, horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Icon(
            Icons.Outlined.Key,
            null,
            tint = if (configured) p.blue else p.muted,
            modifier = Modifier.size(18.dp),
          )
          Column(Modifier.weight(1f)) {
            Text(
              provider.label,
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              provider.envName,
              fontSize = 11.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          StatusDot(configured)
          Text(
            if (configured) "已配置" else "未配置",
            fontSize = 12.sp,
            fontWeight = FontWeight(650),
            color = if (configured) p.blue else p.muted,
          )
        }
      }
    }

    // —— 默认模型 ——
    SectionCard("默认模型") {
      Text(
        "会话默认使用的模型：provider 为内置 id（deepseek / openai / anthropic / google / openrouter / groq / xai / mistral）或自定义供应商标识。",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      val current = if (config.defaultProvider != null) {
        "${config.defaultProvider} / ${config.defaultModel}"
      } else "跟随 dsh 默认（deepseek-official / deepseek-flash）"
      Row(
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(DshmRadius.control.dp))
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
          ) { defaultDialog = true }
          .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            "会话默认模型",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            current,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = if (config.defaultProvider != null) p.blue else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text("修改", fontSize = 12.sp, color = p.blue, fontWeight = FontWeight(650))
      }
    }

    // —— 自定义供应商 ——
    SectionCard("自定义供应商") {
      Text(
        "接入任意 OpenAI 兼容 / Anthropic 兼容中转。声明后可在 dsh 中按「供应商标识 / 模型 ID」选用。",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      config.customProviders.forEach { provider ->
        Column(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshmRadius.control.dp))
            .background(p.surfaceMuted)
            .border(1.dp, p.line, RoundedCornerShape(DshmRadius.control.dp))
            .padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(
                provider.name,
                fontSize = 14.sp,
                fontWeight = FontWeight(650),
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                provider.id + " · " + provider.api,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                provider.baseUrl,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
              )
            }
            IconButton(onClick = { editingCustom = provider }, modifier = Modifier.size(34.dp)) {
              Icon(Icons.Outlined.Edit, "编辑", tint = p.muted, modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = {
              val next = config.copy(
                customProviders = config.customProviders.filterNot { it.id == provider.id },
                customKeys = config.customKeys - provider.id,
                defaultProvider = config.defaultProvider?.takeIf { it != provider.id },
                defaultModel = if (config.defaultProvider == provider.id) null else config.defaultModel,
              )
              persist(next, "已删除供应商「${provider.name}」")
            }, modifier = Modifier.size(34.dp)) {
              Icon(Icons.Outlined.Delete, "删除", tint = p.muted, modifier = Modifier.size(17.dp))
            }
          }
          val keySet = config.customKeys[provider.id]?.isNotBlank() == true
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(keySet)
            Text(
              if (keySet) "Key 已配置" else "未配置 Key",
              fontSize = 12.sp,
              color = if (keySet) p.blue else p.muted,
            )
            Spacer(Modifier.weight(1f))
            ProbeButton(providerLabel = provider.name, baseUrl = provider.baseUrl, apiStyle = provider.api, apiKey = config.customKeys[provider.id]) { msg, danger ->
              notice = msg; noticeDanger = danger
            }
          }
        }
      }
      SecondaryButton(
        if (config.customProviders.isEmpty()) "添加自定义供应商" else "添加",
        { addingCustom = true },
        Modifier.fillMaxWidth(),
      )
    }

    // —— 生效说明 ——
    SectionCard("生效方式") {
      Text(
        "保存后配置立即写入容器。引擎未运行时，下次启动自动生效；引擎运行中，重启引擎（环境页）后生效。",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  // ---- 内置供应商 Key 编辑弹窗 ----
  keyEditFor?.let { provider ->
    KeyEditDialog(
      provider = provider,
      initial = config.builtinKeys[provider.id].orEmpty(),
      onDismiss = { keyEditFor = null },
      onSave = { key ->
        keyEditFor = null
        val next = config.copy(
          builtinKeys = if (key.isBlank()) config.builtinKeys - provider.id else config.builtinKeys + (provider.id to key),
        )
        persist(next, "${provider.label} 的 API Key 已保存并同步容器")
      },
      onTest = { key, onResult ->
        scope.launch(Dispatchers.IO) {
          val r = ProviderProbe.probe(provider.baseUrl, provider.apiStyle.name.lowercase(), key.ifBlank { null })
          withContext(Dispatchers.Main) { onResult(r) }
        }
      },
    )
  }

  // ---- 默认模型编辑弹窗 ----
  if (defaultDialog) {
    var providerDraft by remember { mutableStateOf(config.defaultProvider.orEmpty()) }
    var modelDraft by remember { mutableStateOf(config.defaultModel.orEmpty()) }
    DshmDialog(
      onDismiss = { defaultDialog = false },
      title = "默认模型",
      message = "例如 provider=deepseek、model=deepseek-chat；或自定义供应商 id 与其模型。留空恢复 dsh 默认。",
      confirmLabel = "保存",
      onConfirm = {
        defaultDialog = false
        val next = config.copy(
          defaultProvider = providerDraft.trim().takeIf { it.isNotBlank() },
          defaultModel = modelDraft.trim().takeIf { it.isNotBlank() },
        )
        persist(next, "默认模型已保存")
      },
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DshmInputField(providerDraft, { providerDraft = it.trim().lowercase() }, "Provider ID", hint = "deepseek / openai / 自定义 id")
        DshmInputField(modelDraft, { modelDraft = it.trim() }, "Model ID", hint = "如 deepseek-chat")
      }
    }
  }

  // ---- 自定义供应商编辑/新增弹窗 ----
  if (addingCustom || editingCustom != null) {
    CustomProviderDialog(
      initial = editingCustom,
      initialKey = editingCustom?.let { config.customKeys[it.id] }.orEmpty(),
      existingIds = config.customProviders.map { it.id }.toSet() - editingCustom?.id.orEmpty(),
      onDismiss = { addingCustom = false; editingCustom = null },
      onSave = { provider, key ->
        addingCustom = false
        val old = editingCustom
        editingCustom = null
        val others = config.customProviders.filterNot { it.id == provider.id || it.id == old?.id }
        val next = config.copy(
          customProviders = others + provider,
          customKeys = config.customKeys
            .let { if (old != null) it - old.id else it }
            .plus(provider.id to key),
          // 编辑中改了 id：清理旧 id 的默认模型引用
          defaultProvider = when {
            config.defaultProvider == old?.id && old?.id != provider.id -> null
            else -> config.defaultProvider
          },
          defaultModel = if (config.defaultProvider == old?.id && old?.id != provider.id) null else config.defaultModel,
        )
        persist(next, "供应商「${provider.name}」已保存并同步容器")
      },
      onTest = { provider, key, onResult ->
        scope.launch(Dispatchers.IO) {
          val r = ProviderProbe.probe(provider.baseUrl, provider.api, key.ifBlank { null })
          withContext(Dispatchers.Main) { onResult(r) }
        }
      },
    )
  }
}

@Composable
private fun StatusDot(configured: Boolean) {
  val p = LocalDshmPalette.current
  Box(
    Modifier
      .size(8.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(if (configured) p.blue else p.line),
  )
}

/** 连通性测试小按钮（结果走外层 notice 展示）。 */
@Composable
private fun ProbeButton(
  providerLabel: String,
  baseUrl: String,
  apiStyle: String,
  apiKey: String?,
  onNotice: (String, Boolean) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var running by remember { mutableStateOf(false) }
  Text(
    if (running) "测试中…" else "测试连接",
    fontSize = 12.sp,
    fontWeight = FontWeight(650),
    color = LocalDshmPalette.current.blue,
    modifier = Modifier
      .clip(RoundedCornerShape(999.dp))
      .clickable(enabled = !running) {
        running = true
        onNotice("正在测试 $providerLabel ...", false)
        scope.launch(Dispatchers.IO) {
          val r = ProviderProbe.probe(baseUrl, apiStyle, apiKey)
          withContext(Dispatchers.Main) {
            running = false
            onNotice(
              "$providerLabel：${r.message}" +
                (if (r.httpStatus != null) " · ${r.latencyMs}ms" else ""),
              !r.ok,
            )
          }
        }
      }
      .padding(horizontal = 10.dp, vertical = 4.dp),
  )
}

/** 内置供应商 Key 编辑（含清空与连通性测试）。 */
@Composable
private fun KeyEditDialog(
  provider: ModelConfigManager.BuiltinProvider,
  initial: String,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
  onTest: (String, (ProviderProbe.Result) -> Unit) -> Unit,
) {
  var draft by rememberSaveable { mutableStateOf(initial) }
  var result by remember { mutableStateOf<ProviderProbe.Result?>(null) }
  DshmDialog(
    onDismiss = onDismiss,
    title = "${provider.label} API Key",
    message = "${provider.envName} · ${provider.baseUrl}",
    confirmLabel = "保存",
    dismissLabel = if (initial.isBlank()) "取消" else "清除",
    onConfirm = { onSave(draft.trim()) },
    onDismissConfirm = { onSave("") },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      DshmInputField(
        value = draft,
        onValueChange = { draft = it },
        label = "API Key",
        hint = "sk-...",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProbeResultLine(result)
      }
      Row {
        Text(
          if (result == null) "测试连接" else "重新测试",
          fontSize = 12.sp,
          fontWeight = FontWeight(650),
          color = LocalDshmPalette.current.blue,
          modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable { onTest(draft.trim()) { result = it } }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
    }
  }
}

@Composable
private fun ProbeResultLine(result: ProviderProbe.Result?) {
  if (result == null) return
  val p = LocalDshmPalette.current
  Text(
    result.message + (if (result.httpStatus != null) " · ${result.latencyMs}ms" else ""),
    fontSize = 12.sp,
    lineHeight = 16.sp,
    color = if (result.ok) p.blue else MaterialTheme.colorScheme.error,
  )
}

/**
 * 自定义供应商编辑器：id / 名称 / 接口类型 / Base URL / Key / 模型列表。
 * 模型行格式：`模型ID[,显示名[,上下文长度[,最大输出]]]`，每行一个。
 */
@Composable
private fun CustomProviderDialog(
  initial: ModelConfigManager.CustomProvider?,
  initialKey: String,
  existingIds: Set<String>,
  onDismiss: () -> Unit,
  onSave: (ModelConfigManager.CustomProvider, String) -> Unit,
  onTest: (ModelConfigManager.CustomProvider, String, (ProviderProbe.Result) -> Unit) -> Unit,
) {
  var idDraft by rememberSaveable { mutableStateOf(initial?.id.orEmpty()) }
  var nameDraft by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
  var apiSelected by rememberSaveable { mutableStateOf(initial?.api?.let { ModelConfigManager.CUSTOM_APIS.indexOf(it).coerceAtLeast(0) } ?: 0) }
  var urlDraft by rememberSaveable { mutableStateOf(initial?.baseUrl.orEmpty()) }
  var keyDraft by rememberSaveable { mutableStateOf(initialKey) }
  var modelsDraft by rememberSaveable {
    mutableStateOf(
      initial?.models?.joinToString("\n") { m ->
        listOf(m.id, m.name, m.contextWindow.toString(), m.maxTokens.toString())
          .joinToString(",")
      }.orEmpty(),
    )
  }
  var error by remember { mutableStateOf<String?>(null) }
  var result by remember { mutableStateOf<ProviderProbe.Result?>(null) }

  fun build(): ModelConfigManager.CustomProvider? {
    error = null
    val id = idDraft.trim()
    val name = nameDraft.trim().ifBlank { id }
    val url = urlDraft.trim()
    try {
      if (!Regex("^[a-z][a-z0-9-]{1,31}$").matches(id)) {
        throw IllegalArgumentException("供应商标识需为小写字母/数字/短横线（字母开头，2-32 位）")
      }
      if (id in existingIds) throw IllegalArgumentException("供应商标识「$id」已存在")
      if (!Regex("^https://[A-Za-z0-9.\\-:_/]+$").matches(url)) {
        throw IllegalArgumentException("Base URL 必须是 https:// 地址")
      }
      val models = modelsDraft.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { line ->
          val parts = line.split(',').map { it.trim() }
          val mid = parts.getOrNull(0).orEmpty()
          if (!Regex("^[A-Za-z0-9._/-]{1,128}$").matches(mid)) {
            throw IllegalArgumentException("模型 ID「$mid」无效（字母/数字/点/短横线/斜杠）")
          }
          val cw = parts.getOrNull(2)?.toIntOrNull() ?: 65536
          val mt = parts.getOrNull(3)?.toIntOrNull() ?: 8192
          if (cw !in 1024..10_000_000) {
            throw IllegalArgumentException("模型「$mid」上下文长度需在 1024-10000000 之间")
          }
          if (mt !in 1..cw) {
            throw IllegalArgumentException("模型「$mid」最大输出需在 1-上下文长度之间")
          }
          ModelConfigManager.CustomModel(
            id = mid,
            name = parts.getOrNull(1)?.ifBlank { null } ?: mid,
            contextWindow = cw,
            maxTokens = mt,
          )
        }
      if (models.isEmpty()) throw IllegalArgumentException("至少填写 1 个模型")
      val ids = HashSet<String>()
      models.forEach { m ->
        if (!ids.add(m.id)) throw IllegalArgumentException("模型 ID「${m.id}」重复")
      }
      return ModelConfigManager.CustomProvider(
        id = id,
        name = name,
        api = ModelConfigManager.CUSTOM_APIS[apiSelected],
        baseUrl = url,
        models = models,
      )
    } catch (t: IllegalArgumentException) {
      error = t.message
      return null
    } catch (t: Throwable) {
      error = t.message ?: "校验失败"
      return null
    }
  }

  DshmDialog(
    onDismiss = onDismiss,
    title = if (initial == null) "添加自定义供应商" else "编辑自定义供应商",
    confirmLabel = "保存",
    onConfirm = {
      val built = build() ?: return@DshmDialog
      onSave(built, keyDraft.trim())
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      DshmInputField(idDraft, { idDraft = it.trim().lowercase() }, "供应商标识（唯一）", hint = "如 my-relay")
      DshmInputField(nameDraft, { nameDraft = it }, "显示名称", hint = "默认同标识")
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("接口类型", fontSize = 12.sp, fontWeight = FontWeight(650), color = MaterialTheme.colorScheme.onSurface)
        Segmented(
          options = listOf("OpenAI", "Responses", "Anthropic"),
          selected = apiSelected,
          onSelect = { apiSelected = it },
        )
      }
      DshmInputField(urlDraft, { urlDraft = it.trim() }, "Base URL", hint = "https://api.example.com/v1")
      DshmInputField(
        keyDraft, { keyDraft = it }, "API Key",
        hint = "写入容器凭据文档",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
      )
      DshmInputField(
        modelsDraft, { modelsDraft = it }, "模型列表（每行一个）",
        hint = "模型ID,显示名,上下文,最大输出\n如：gpt-4o,GPT-4o,128000,16384",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
      )
      if (error != null) InlineAlert(title = "校验未通过", message = error!!, danger = true)
      Row {
        Text(
          "测试连接",
          fontSize = 12.sp,
          fontWeight = FontWeight(650),
          color = LocalDshmPalette.current.blue,
          modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable {
              val built = build() ?: return@clickable
              onTest(built, keyDraft.trim()) { result = it }
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
      ProbeResultLine(result)
    }
  }
}
