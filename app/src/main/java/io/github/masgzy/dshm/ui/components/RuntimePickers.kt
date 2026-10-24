package io.github.masgzy.dshm.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.masgzy.dshm.engine.DistroCatalog
import io.github.masgzy.dshm.engine.DshCatalog
import io.github.masgzy.dshm.engine.NodeCatalog
import io.github.masgzy.dshm.ui.AppViewModel
import io.github.masgzy.dshm.ui.theme.DshmRadius
import io.github.masgzy.dshm.ui.theme.LocalDshmPalette
import kotlinx.coroutines.CancellationException

/**
 * 运行时选择器对话框组：
 *  - [DistroPickerDialog] 容器系统（五个内置 + 自定义 Docker 镜像引用）；
 *  - [NodeVersionPickerDialog] Node.js 版本（远端 dist 目录 + 手动输入）；
 *  - [DshVersionPickerDialog] dsh 版本（npm packument + 手动输入 / 跟随 latest）。
 *
 * 均以 DshmDialog 底部弹卡形态呈现（对齐 dphmoblie 设置弹层）。
 */

// ---------------------------------------------------------------- 容器系统

@Composable
fun DistroPickerDialog(
  viewModel: AppViewModel,
  onDismiss: () -> Unit,
) {
  val p = LocalDshmPalette.current
  var draftId by remember { mutableStateOf(viewModel.distroId) }
  var draftRef by remember { mutableStateOf(viewModel.customImageRef) }
  val customInvalid = draftId == DistroCatalog.CUSTOM_ID &&
    !DockerRefValid(draftRef)

  DshmDialog(
    onDismiss = onDismiss,
    title = "容器系统",
    message = "选择运行环境的发行版；自定义项接受任意公开 Docker 镜像引用" +
      "（如 ubuntu:24.04、ghcr.io/example/image:tag）。",
    icon = Icons.Outlined.Dns,
    confirmLabel = "保存",
    confirmEnabled = !customInvalid,
    onConfirm = {
      viewModel.distroId = draftId
      if (draftId == DistroCatalog.CUSTOM_ID) viewModel.customImageRef = draftRef.trim()
      onDismiss()
    },
  ) {
    Column(
      Modifier
        .heightIn(max = 420.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      DistroCatalog.ALL.forEach { d ->
        SelectRow(
          title = d.label,
          subtitle = d.detail,
          selected = draftId == d.id,
          onClick = { draftId = d.id },
        )
      }
      SelectRow(
        title = "自定义镜像",
        subtitle = "从 Docker registry 拉取任意公开镜像",
        selected = draftId == DistroCatalog.CUSTOM_ID,
        onClick = { draftId = DistroCatalog.CUSTOM_ID },
      )
      if (draftId == DistroCatalog.CUSTOM_ID) {
        DshmInputField(
          value = draftRef,
          onValueChange = { draftRef = it.trim() },
          label = "镜像引用",
          hint = "例：debian:stable-slim 或 ghcr.io/xxx/yyy:tag",
        )
        if (customInvalid) {
          Text(
            "镜像引用格式无效（示例：alpine:3.19）",
            fontSize = 12.sp,
            color = p.red,
          )
        }
      }
      Text(
        "切换容器系统后需「重置运行环境」并重新安装（工作区文件保留）。",
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        color = p.muted,
      )
    }
  }
}

private fun DockerRefValid(raw: String): Boolean =
  io.github.masgzy.dshm.engine.DockerRegistryClient.RefParser.isValid(raw)

// ---------------------------------------------------------------- Node.js

@Composable
fun NodeVersionPickerDialog(
  viewModel: AppViewModel,
  onDismiss: () -> Unit,
) {
  val p = LocalDshmPalette.current
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  var versions by remember { mutableStateOf<List<NodeCatalog.Entry>>(emptyList()) }
  var draft by remember { mutableStateOf(viewModel.nodeVersion) }
  var manualDraft by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    try {
      versions = viewModel.fetchNodeVersions()
      loading = false
    } catch (c: CancellationException) {
      throw c
    } catch (t: Throwable) {
      loading = false
      error = t.message ?: "目录获取失败"
    }
  }

  DshmDialog(
    onDismiss = onDismiss,
    title = "Node.js 版本",
    message = "默认 v24 LTS；可切换任意官方发布版本（linux-arm64 构建）。",
    icon = Icons.Outlined.Memory,
    confirmLabel = "保存",
    confirmEnabled = NodeCatalog.normalize(draft) != null,
    onConfirm = {
      NodeCatalog.normalize(draft)?.let {
        viewModel.nodeVersion = it
        onDismiss()
      }
    },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      when {
        loading -> Text(
          "正在获取官方版本目录（nodejs.org / npmmirror）…",
          fontSize = 12.5.sp, color = p.muted,
        )
        error != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("版本目录获取失败：$error", fontSize = 12.sp, color = p.amber)
          Text("可从下方手动输入版本号（如 v22.14.0）。", fontSize = 12.sp, color = p.muted)
        }
        else -> VersionList(
          items = versions.take(80).map { e ->
            VersionRowData(
              key = e.version,
              badge = if (e.lts) "LTS" else null,
              selected = draft == e.version,
            ) { draft = e.version }
          },
        )
      }
      if (loading || error != null) {
        // 目录不可达时的离线候选
        VersionList(
          items = viewModel.nodeFallbackVersions.map { v ->
            VersionRowData(key = v, badge = null, selected = draft == v) { draft = v }
          },
        )
      }
      DshmInputField(
        value = manualDraft,
        onValueChange = { manualDraft = it.trim() },
        label = "手动输入",
        hint = "v24.22.0",
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
          keyboardType = KeyboardType.Ascii,
        ),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(
          "使用输入值",
          {
            NodeCatalog.normalize(manualDraft)?.let { draft = it }
          },
          enabled = NodeCatalog.normalize(manualDraft) != null,
        )
        Text(
          if (NodeCatalog.normalize(draft) != null) "当前选择：$draft"
          else "请选择或输入有效版本",
          fontSize = 12.sp,
          color = p.muted,
        )
      }
      Text(
        "切换后需「重置运行环境」并重新安装生效（工作区文件保留）。",
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        color = p.muted,
      )
    }
  }
}

// ---------------------------------------------------------------- dsh

@Composable
fun DshVersionPickerDialog(
  viewModel: AppViewModel,
  onDismiss: () -> Unit,
) {
  val p = LocalDshmPalette.current
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  var versions by remember { mutableStateOf<List<DshCatalog.Entry>>(emptyList()) }
  var draft by remember { mutableStateOf(viewModel.dshVersion) } // "" = latest
  var manualDraft by remember { mutableStateOf("") }

  LaunchedEffect(Unit) {
    try {
      versions = viewModel.fetchDshVersions()
      loading = false
    } catch (c: CancellationException) {
      throw c
    } catch (t: Throwable) {
      loading = false
      error = t.message ?: "目录获取失败"
    }
  }

  fun confirm(v: String) {
    viewModel.dshVersion = v
    onDismiss()
  }

  DshmDialog(
    onDismiss = onDismiss,
    title = "dsh 版本",
    message = "@deepseek-ai/dsh 处于 developer preview；默认锁定已验证版本，" +
      "也可跟随 npm latest 或指定任意历史版本。",
    icon = Icons.Outlined.CloudDownload,
    confirmLabel = "保存",
    confirmEnabled = draft.isEmpty() || DshCatalog.isValidVersion(draft),
    onConfirm = { confirm(draft) },
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      // 跟随最新版
      SelectRow(
        title = "跟随 npm latest",
        subtitle = "每次重装容器时安装最新发布版",
        selected = draft.isEmpty(),
        onClick = { draft = "" },
      )
      when {
        loading -> Text(
          "正在获取 npm 版本目录（${if (viewModel.npmRegistry.contains("npmmirror")) "npmmirror" else "npmjs"}）…",
          fontSize = 12.5.sp, color = p.muted,
        )
        error != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("版本目录获取失败：$error", fontSize = 12.sp, color = p.amber)
          Text("可从下方手动输入版本号（如 0.2.1-rc.2）。", fontSize = 12.sp, color = p.muted)
        }
        else -> VersionList(
          items = versions.map { e ->
            VersionRowData(
              key = e.version,
              badge = e.tags.firstOrNull()?.uppercase(),
              selected = draft == e.version,
            ) { draft = e.version }
          },
        )
      }
      if (loading || error != null) {
        VersionList(
          items = viewModel.dshFallbackVersions.map { v ->
            VersionRowData(key = v, badge = null, selected = draft == v) { draft = v }
          },
        )
      }
      DshmInputField(
        value = manualDraft,
        onValueChange = { manualDraft = it.trim() },
        label = "手动输入",
        hint = "0.2.1-rc.2",
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
          keyboardType = KeyboardType.Ascii,
        ),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(
          "使用输入值",
          {
            if (DshCatalog.isValidVersion(manualDraft)) draft = manualDraft
          },
          enabled = DshCatalog.isValidVersion(manualDraft),
        )
        Text(
          when {
            draft.isEmpty() -> "当前选择：latest"
            else -> "当前选择：$draft"
          },
          fontSize = 12.sp,
          color = p.muted,
        )
      }
      Text(
        "切换后需「重置运行环境」并重新安装生效（工作区文件保留）。",
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        color = p.muted,
      )
    }
  }
}

// ---------------------------------------------------------------- 通用件

private data class VersionRowData(
  val key: String,
  val badge: String?,
  val selected: Boolean,
  val onClick: () -> Unit,
)

/** 版本滚动列表（对话卡片内 220dp 上限）。 */
@Composable
private fun VersionList(items: List<VersionRowData>) {
  LazyColumn(Modifier.heightIn(max = 220.dp)) {
    items(items.size) { i ->
      val it = items[i]
      SelectRow(
        title = it.key,
        badge = it.badge,
        selected = it.selected,
        onClick = it.onClick,
      )
    }
  }
}

/** 单行选择项：标题 + 徽章（可选）+ 副标题（可选）+ 右侧对勾。 */
@Composable
private fun SelectRow(
  title: String,
  subtitle: String? = null,
  badge: String? = null,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val p = LocalDshmPalette.current
  val shape = RoundedCornerShape(DshmRadius.control.dp)
  Row(
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(if (selected) p.blueSoft else p.surfaceMuted, shape)
      .border(1.dp, if (selected) p.blue else p.line, shape)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
      ) { onClick() }
      .padding(horizontal = 13.dp, vertical = 9.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
          title,
          fontSize = 13.5.sp,
          fontWeight = FontWeight(650),
          color = if (selected) p.blue else MaterialTheme.colorScheme.onSurface,
        )
        if (badge != null) {
          Box(
            Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(p.blueSoft, RoundedCornerShape(6.dp))
              .padding(horizontal = 5.dp, vertical = 1.dp),
          ) {
            Text(badge, fontSize = 9.5.sp, fontWeight = FontWeight(700), color = p.blue)
          }
        }
      }
      if (!subtitle.isNullOrBlank()) {
        Text(subtitle, fontSize = 11.sp, color = p.muted)
      }
    }
    if (selected) {
      Icon(Icons.Outlined.Check, null, tint = p.blue, modifier = Modifier.size(17.dp))
    }
  }
}
