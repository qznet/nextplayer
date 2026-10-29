package dev.anilbeesetti.nextplayer.core.media.network.datasource

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking

/**
 * Reads a file on a saved network connection (SMB/FTP/SFTP/WebDAV) for the Media3 player.
 *
 * Media3 ships no data source for these protocols, so this adapts [dev.anilbeesetti.nextplayer.core.media.network.NetworkClient.openStream],
 * which can start at an arbitrary byte offset and therefore supports seeking directly. The player
 * closes and reopens the source at a new offset for each seek.
 *
 * SMB (and the other) sessions can be silently dropped by the server, NAT or Wi-Fi mid-playback:
 * the connection object still reports "connected" while the TCP socket is already dead, so a plain
 * read() throws and Media3 surfaces a "Source error". To avoid that, read() retries transparently —
 * it closes the broken stream, forces the shared session to reconnect, reopens the stream at the
 * exact byte we were at, and continues. The player never sees the hiccup.
 */
@UnstableApi
class NetworkDataSource(private val sessions: NetworkSessions) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var filePath: String? = null
    private var specPosition: Long = 0
    private var specLength: Long = C.LENGTH_UNSET.toLong()
    private var stream: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false
    /** Absolute byte offset of the next byte we expect to read from the file. */
    private var resumePosition: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        filePath = null
        specPosition = dataSpec.position
        specLength = dataSpec.length
        resumePosition = dataSpec.position
        transferInitializing(dataSpec)

        try {
            runBlocking { openAt(dataSpec) }
        } catch (e: IOException) {
            Log.w(TAG, "open() failed, retrying after reconnect", e)
            // One recovery attempt: force a fresh connection, then retry once.
            try {
                runBlocking { sessions.reconnect() }
                runBlocking { openAt(dataSpec) }
            } catch (e2: IOException) {
                throw e2
            } catch (e2: Exception) {
                throw IOException("Failed to open ${dataSpec.uri}", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "open() failed", e)
            throw IOException("Failed to open ${dataSpec.uri}", e)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private suspend fun openAt(dataSpec: DataSpec) {
        val target = sessions.target(dataSpec.uri)
        filePath = target.filePath
        val fileSize = target.client.fileSize(target.filePath)
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            fileSize >= 0 -> (fileSize - dataSpec.position).coerceAtLeast(minimumValue = 0)
            else -> C.LENGTH_UNSET.toLong()
        }
        stream = target.client.openStream(target.filePath, dataSpec.position)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val readLength = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(
                length.toLong(),
                b = bytesRemaining,
            ).toInt()
        }

        var attempt = 0
        while (true) {
            try {
                val bytesRead = stream?.read(
                    buffer,
                    offset,
                    readLength,
                ) ?: return C.RESULT_END_OF_INPUT
                if (bytesRead == -1) return C.RESULT_END_OF_INPUT
                resumePosition += bytesRead
                if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= bytesRead
                bytesTransferred(bytesRead)
                return bytesRead
            } catch (e: IOException) {
                if (attempt >= READ_RETRY_COUNT) {
                    Log.e(
                        TAG,
                        "read() failed after $attempt retries at byte $resumePosition",
                        e,
                    )
                    throw e
                }
                attempt++
                Log.w(
                    TAG,
                    "read() failed at byte $resumePosition, reconnecting (attempt $attempt)",
                    e,
                )
                recoverStream()
            }
        }
    }

    /**
     * Reopen the stream at [resumePosition] on a freshly reconnected session, so the next read
     * continues exactly where the previous one stopped. Only reached on an IOException, i.e. the
     * failure path.
     */
    private fun recoverStream() {
        runBlocking {
            try {
                stream?.close()
            } catch (_: Exception) {
                // ignore
            }
            stream = null
            sessions.reconnect()
            val target = sessions.target(uri ?: throw IOException("uri lost during recovery"))
            val fp = filePath ?: target.filePath
            bytesRemaining = if (specLength != C.LENGTH_UNSET.toLong()) {
                (specLength - (resumePosition - specPosition)).coerceAtLeast(minimumValue = 0)
            } else {
                C.LENGTH_UNSET.toLong()
            }
            stream = target.client.openStream(fp, resumePosition)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            stream?.close()
        } finally {
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        private const val TAG = "NetworkDataSource"
        /** How many times read() may transparently reconnect before giving up. */
        private const val READ_RETRY_COUNT = 2
    }
}
