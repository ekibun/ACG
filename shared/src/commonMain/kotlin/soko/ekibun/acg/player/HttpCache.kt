package soko.ekibun.acg.player

import androidx.collection.LruCache
import androidx.collection.lruCache

/**
 * **进程级**的字节缓存：按「流 URL + 块号」留住已经下过的字节，seek 回到读过的区域不必再走网络。
 *
 * 键必须带 URL：本对象跨播放会话存活，HLS 每个分片的偏移空间都从 0 开始。
 * 只当加速使 —— 命中不了只多一次请求，任何情况下不许改变读出来的字节。
 *
 * 约束与取舍见 `docs/development/playback-invariants.md` §5.7。
 */
internal object HttpCache {
  /**
   * 一块的字节数，与 avio 初始缓冲对齐（`ffmpeg.cpp` 的 `io_open` 写死 32768）。
   * 也是 [HttpIO.read] 的交付上限：一次最多给一块，更大的 `len` 由后续读接着要。
   */
  const val BLOCK_BYTES: Int = 32 * 1024

  /** 预算，单位是**块数**（512 块 ≈ 16 MiB 满块）。半块与满块同权，实际内存只少不多。 */
  const val CACHE_SIZE: Int = 512

  private val blocks: LruCache<BlockKey, ByteArray> = lruCache(CACHE_SIZE)

  /** 每条流的长度。用 [LruCache] 而不是 `HashMap`：本对象跨线程读写。淘汰的代价见 §5.7 第 8 条。 */
  private val lengths = lruCache<String, Long>(CACHE_SIZE)

  /** 键：一条流的一个块。`url` 是请求用的 URL，不是重定向后的落点。 */
  private data class BlockKey(
    val url: String,
    val index: Int,
  )

  /** [pos] 所在块的块首。会话的 range 请求从块首起，见 [HttpIO.getRange]。 */
  fun blockStart(pos: Int): Int = pos / BLOCK_BYTES * BLOCK_BYTES

  /** 块 [index] 的内容（末尾那块可能不满）；没缓存返回 null。 */
  fun chunk(
    url: String,
    index: Int,
  ): ByteArray? = blocks[BlockKey(url, index)]

  /** 收下一块字节。**调用方不许再改这个数组** —— 它直接进缓存，改了就是静默串数据。 */
  fun putChunk(
    url: String,
    index: Int,
    bytes: ByteArray,
  ) {
    blocks.put(BlockKey(url, index), bytes)
  }

  /** 流总长度；没记过返回 -1（与 `Http.Response.contentLength` 的「未知」同约定）。 */
  fun length(url: String): Long = lengths[url] ?: -1L

  /** 记下流总长度。长度未知时不记 —— 与「没记过」等价，别往表里塞 -1。 */
  fun putLength(
    url: String,
    length: Long,
  ) {
    if (length >= 0L) lengths.put(url, length)
  }

  /** 丢掉某条流的全部块与长度，别的流不受影响。 */
  fun clear(url: String) {
    for (key in blocks.snapshot().keys) {
      if (key.url == url) blocks.remove(key)
    }
    lengths.remove(url)
  }
}
