package io.github.masgzy.dshm.engine

/**
 * 容器系统目录：内置五种发行版 + 自定义 Docker 镜像。
 *
 * 每种发行版声明两条获取路径：
 *  - [cdimage]：发行方直链 tar.gz（如 Ubuntu 官方 ubuntu-base），带锁定 SHA-256，优先；
 *  - [imageRef]：Docker Registry v2 引用（经 [DockerRegistryClient] 拉取，
 *    逐层 digest 校验），作为兜底或唯一路径。
 *
 * family 决定容器内 bootstrap 策略：
 *  - GLIBC（Debian/Ubuntu/大多数自定义镜像）：宿主下载 Node 官方 glibc tarball 注入；
 *  - ALPINE（musl）：官方 Node tarball 不兼容 musl，改为容器内 `apk add nodejs npm`。
 */
enum class DistroFamily { GLIBC, ALPINE }

/** 发行方直链 rootfs（cdimage / cloud 镜像），SHA-256 锁定。 */
data class CdimageRef(
  val release: String,
  val fileName: String,
  val sha256: String,
  /** 直链镜像列表（官方 + 国内加速），顺序即回退顺序。 */
  val mirrors: List<String>,
  /** 直链失败时回退的 Docker 引用。 */
  val fallbackRef: String,
)

data class Distro(
  /** 稳定 ID（SharedPreferences 存储，勿改）。 */
  val id: String,
  /** 主标题（如 "Debian 13"）。 */
  val label: String,
  /** 副标题（代号 / 大小提示）。 */
  val detail: String,
  val family: DistroFamily,
  /** Docker Registry 引用（"debian:13"）；cdimage 优先的发行版可为非空兜底。 */
  val imageRef: String,
  /** cdimage 直链；null = 仅走 registry。 */
  val cdimage: CdimageRef? = null,
  /** 架构（当前仅 arm64）。 */
  val arch: String = "arm64",
) {
  /** 对外展示的完整描述（环境详情 / 设置页）。 */
  val display: String get() = if (detail.isBlank()) label else "$label · $detail"
}

/** 内置发行版目录与自定义项。 */
object DistroCatalog {

  /** 内置 Ubuntu 26.04 cdimage 直链（与 release.yml rootfs 构建锁同源）。 */
  private val UBUNTU26_CDIMAGE = CdimageRef(
    release = "26.04",
    fileName = "ubuntu-base-26.04.1-base-arm64.tar.gz",
    sha256 = "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd",
    mirrors = listOf(
      "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz",
      "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz",
    ),
    fallbackRef = "ubuntu:26.04",
  )

  const val CUSTOM_ID = "custom"

  /** 五个内置项 + 自定义，顺序即 UI 展示顺序。 */
  val ALL: List<Distro> = listOf(
    Distro(
      id = "debian13",
      label = "Debian 13",
      detail = "trixie",
      family = DistroFamily.GLIBC,
      imageRef = "debian:13",
    ),
    Distro(
      id = "debian12",
      label = "Debian 12",
      detail = "bookworm",
      family = DistroFamily.GLIBC,
      imageRef = "debian:12",
    ),
    Distro(
      id = "ubuntu26",
      label = "Ubuntu 26.04",
      detail = "resolute · 默认",
      family = DistroFamily.GLIBC,
      imageRef = "ubuntu:26.04",
      cdimage = UBUNTU26_CDIMAGE,
    ),
    Distro(
      id = "ubuntu24",
      label = "Ubuntu 24.04",
      detail = "noble",
      family = DistroFamily.GLIBC,
      imageRef = "ubuntu:24.04",
    ),
    Distro(
      id = "alpine3",
      label = "Alpine 3.22",
      detail = "musl · 极小体积",
      family = DistroFamily.ALPINE,
      imageRef = "alpine:3.22",
    ),
  )

  /** 默认发行版（保持既有行为：Ubuntu 26.04 + cdimage 直链）。 */
  val DEFAULT: Distro = ALL.first { it.id == "ubuntu26" }

  fun byId(id: String?): Distro? = ALL.firstOrNull { it.id == id }

  /** 解析用户当前选择（含自定义引用）；无效时回退默认。 */
  fun resolve(id: String?, customRef: String?): Distro =
    if (id == CUSTOM_ID && !customRef.isNullOrBlank()) {
      Distro(
        id = CUSTOM_ID,
        label = "自定义镜像",
        detail = customRef.trim(),
        family = DistroFamily.GLIBC, // musl 自定义镜像在 bootstrap 处给出明确报错
        imageRef = customRef.trim(),
      )
    } else {
      byId(id) ?: DEFAULT
    }
}
