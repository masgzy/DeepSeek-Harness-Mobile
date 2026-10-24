package io.github.masgzy.dshm.util

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * rootfs / Node.js 压缩包解包器（.tar.gz / .tar.xz / .tar 自动嗅探）。
 *
 * 安全设计（参照 dphmoblie SafeRootfsExtractor 的思路）：
 *  - 拒绝绝对路径与 "../" 穿越，所有条目必须落在目标目录内；
 *  - 保留 tar 内的 POSIX 权限位（可执行位对 node/proot 至关重要）；
 *  - 正确处理符号链接与硬链接（ubuntu-base 内含大量 /bin -> usr/bin 类链接）。
 */
object TarExtractor {

  fun extract(archive: File, destDir: File, onProgress: (Long) -> Unit = {}): Boolean =
    try {
      extract(archive.inputStream().buffered(), archive.length(), destDir, onProgress)
      true
    } catch (t: Throwable) {
      AppLog.log("tar", "解压失败: ${archive.name}", t)
      false
    }

  fun extract(
    input: InputStream,
    hintSize: Long,
    destDir: File,
    onProgress: (Long) -> Unit = {},
  ) {
    destDir.mkdirs()
    input.use { raw ->
      TarArchiveInputStream(decompress(raw)).use { tar ->
        var entry = tar.nextTarEntry
        var count = 0L
        while (entry != null) {
          val name = entry.name.removePrefix("./")
          checkEntryPath(destDir, name)
          val target = File(destDir, name)
          when {
            entry.isDirectory -> {
              target.mkdirs()
              chmod(target, safeMode(entry.mode, isDir = true))
            }
            entry.isSymbolicLink -> {
              target.parentFile?.mkdirs()
              target.delete()
              Files.createSymbolicLink(
                target.toPath(),
                Paths.get(entry.linkName),
              )
            }
            entry.isLink -> {
              // 硬链接：目标一定在本包内，找到后直接复制内容
              val linkTarget = File(destDir, entry.linkName.removePrefix("./"))
              if (linkTarget.isFile) {
                target.parentFile?.mkdirs()
                linkTarget.copyTo(target, overwrite = true)
                chmod(target, safeMode(entry.mode, isDir = false))
              } else {
                AppLog.log("tar", "硬链接目标缺失，跳过: ${entry.name} -> ${entry.linkName}")
              }
            }
            else -> {
              target.parentFile?.mkdirs()
              target.outputStream().use { out -> tar.copyTo(out) }
              chmod(target, safeMode(entry.mode, isDir = false))
            }
          }
          count++
          if (count % 200L == 0L) onProgress(count)
          entry = tar.nextTarEntry
        }
        onProgress(count)
        AppLog.log("tar", "解压完成: $count 个条目 -> ${destDir.absolutePath}")
      }
    }
  }

  /**
   * Docker 镜像 layer tarball 解包（registry 拉取路径专用）：
   * 在普通解包语义上叠加 OCI/Docker whiteout 处理与设备文件跳过。
   *
   *  - `.wh..wh..opq`：不透明目录 —— 清空该目录既有内容（上层声明「以下不继承」）；
   *  - `.wh.<name>`：删除同名文件/目录（上层删除标记）；
   *  - char/block/fifo 设备条目：proot 用户态无需真实设备节点，跳过（镜像内
   *    /dev/console 等由 proot 自带 -b /dev 覆盖）。
   *
   * 路径穿越防护与权限保留与 [extract] 完全一致。
   */
  fun extractLayer(input: InputStream, destDir: File, onProgress: (Long) -> Unit = {}) {
    destDir.mkdirs()
    input.use { raw ->
      TarArchiveInputStream(decompress(raw)).use { tar ->
        var entry = tar.nextTarEntry
        var count = 0L
        while (entry != null) {
          val name = entry.name.removePrefix("./")
          count++
          if (count % 200L == 0L) onProgress(count)
          if (name.isEmpty()) {
            entry = tar.nextTarEntry
            continue
          }
          val base = name.substringAfterLast('/')
          val parentPath = name.substringBeforeLast('/', missingDelimiterValue = "")
          when {
            // 不透明目录：清空父目录既有内容
            base == ".wh..wh..opq" -> {
              val dir = if (parentPath.isEmpty()) destDir else File(destDir, parentPath)
              if (dir.isDirectory) {
                dir.listFiles()?.forEach { it.deleteRecursively() }
                AppLog.log("tar-layer", "opaque dir: /$parentPath")
              }
              entry = tar.nextTarEntry
              continue
            }
            // 删除标记：移除同名文件/目录
            base.startsWith(".wh.") -> {
              val targetName = base.removePrefix(".wh.")
              val target = if (parentPath.isEmpty()) File(destDir, targetName) else File(destDir, "$parentPath/$targetName")
              if (target.exists()) {
                target.deleteRecursively()
                AppLog.log("tar-layer", "whiteout: /$name")
              }
              entry = tar.nextTarEntry
              continue
            }
          }
          checkEntryPath(destDir, name)
          val target = File(destDir, name)
          when {
            // 设备与 FIFO：跳过（不 mknod，proot 下无意义）
            entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> {
              AppLog.log("tar-layer", "跳过设备/FIFO 条目: $name")
            }
            entry.isDirectory -> {
              target.mkdirs()
              chmod(target, safeMode(entry.mode, isDir = true))
            }
            entry.isSymbolicLink -> {
              target.parentFile?.mkdirs()
              target.delete()
              runCatching {
                Files.createSymbolicLink(target.toPath(), Paths.get(entry.linkName))
              }.onFailure { t ->
                AppLog.log("tar-layer", "符号链接创建失败（已存在?）: $name", t)
              }
            }
            entry.isLink -> {
              val linkTarget = File(destDir, entry.linkName.removePrefix("./"))
              if (linkTarget.exists()) {
                target.parentFile?.mkdirs()
                target.delete()
                linkTarget.copyTo(target, overwrite = true)
                chmod(target, safeMode(entry.mode, isDir = false))
              } else {
                AppLog.log("tar-layer", "硬链接目标缺失，跳过: ${entry.name} -> ${entry.linkName}")
              }
            }
            else -> {
              target.parentFile?.mkdirs()
              if (target.isDirectory) target.deleteRecursively() // 文件替换目录
              target.outputStream().use { out -> tar.copyTo(out) }
              chmod(target, safeMode(entry.mode, isDir = false))
            }
          }
          entry = tar.nextTarEntry
        }
        onProgress(count)
        AppLog.log("tar-layer", "layer 解包完成: $count 个条目 -> ${destDir.absolutePath}")
      }
    }
  }

  private fun decompress(raw: InputStream): InputStream {
    raw.mark(4)
    val magic = ByteArray(4)
    val read = raw.read(magic)
    raw.reset()
    val head = if (read > 1) magic else ByteArray(0)
    return when {
      head.size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() ->
        GzipCompressorInputStream(raw) // .tar.gz
      head.size >= 3 && head[0] == 0xfd.toByte() &&
        head[1] == '7'.code.toByte() && head[2] == 'z'.code.toByte() ->
        XZCompressorInputStream(raw) // .tar.xz
      else -> raw // 纯 .tar
    }
  }

  /** 防路径穿越：名称必须解析后仍位于 destDir 之内。 */
  private fun checkEntryPath(destDir: File, name: String) {
    require(name.isNotEmpty()) { "空条目名" }
    require(!name.startsWith("/") && !name.contains("\\0")) { "非法条目名: $name" }
    val canonical = File(destDir, name).canonicalPath
    require(canonical == destDir.canonicalPath || canonical.startsWith(destDir.canonicalPath + File.separator)) {
      "条目越界: $name"
    }
  }

  // 权限位常量（Kotlin 无八进制字面量，用十进制：0777/0700/rwxrwxrwx 等）
  private const val PERM_MASK = 511        // 0o777
  private const val PERM_RWX_OWNER = 448   // 0o700
  private const val PERM_R_OWNER = 256     // 0o400
  private const val PERM_W_OWNER = 128     // 0o200
  private const val PERM_X_OWNER = 64      // 0o100
  private const val PERM_R_GROUP = 32      // 0o040
  private const val PERM_W_GROUP = 16      // 0o020
  private const val PERM_X_GROUP = 8       // 0o010
  private const val PERM_R_OTHER = 4       // 0o004
  private const val PERM_W_OTHER = 2       // 0o002
  private const val PERM_X_OTHER = 1       // 0o001

  private fun safeMode(mode: Int, isDir: Boolean): Int {
    val perm = mode and PERM_MASK
    // 目录至少保证可进入；文件保留执行位即可（属主为应用 uid）
    return if (isDir) perm or PERM_RWX_OWNER else perm
  }

  private fun chmod(target: File, mode: Int) {
    val perms = EnumSet.noneOf(PosixFilePermission::class.java)
    if (mode and PERM_R_OWNER != 0) perms.add(PosixFilePermission.OWNER_READ)
    if (mode and PERM_W_OWNER != 0) perms.add(PosixFilePermission.OWNER_WRITE)
    if (mode and PERM_X_OWNER != 0) perms.add(PosixFilePermission.OWNER_EXECUTE)
    if (mode and PERM_R_GROUP != 0) perms.add(PosixFilePermission.GROUP_READ)
    if (mode and PERM_W_GROUP != 0) perms.add(PosixFilePermission.GROUP_WRITE)
    if (mode and PERM_X_GROUP != 0) perms.add(PosixFilePermission.GROUP_EXECUTE)
    if (mode and PERM_R_OTHER != 0) perms.add(PosixFilePermission.OTHERS_READ)
    if (mode and PERM_W_OTHER != 0) perms.add(PosixFilePermission.OTHERS_WRITE)
    if (mode and PERM_X_OTHER != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE)
    runCatching { Files.setPosixFilePermissions(target.toPath(), perms) }
  }
}
