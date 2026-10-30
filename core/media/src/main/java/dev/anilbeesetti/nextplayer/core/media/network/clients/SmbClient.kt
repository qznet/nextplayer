package dev.anilbeesetti.nextplayer.core.media.network.clients

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipeShare
import com.rapid7.client.dcerpc.Interface
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransport
import com.rapid7.helper.smbj.share.NamedPipe
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SMB2/3 client backed by smbj. [NetworkConnection.path] holds a share name or `/` to browse
 * the server's disk shares. Server-root browse paths include the share as their first segment;
 * paths for a named-share connection remain relative to that share.
 */
class SmbClient internal constructor(
    private val connection: NetworkConnection,
    private val createClient: (SmbConfig) -> SMBClient,
    private val listShares: (Session) -> List<NetworkFile>,
) : NetworkClient {

    constructor(connection: NetworkConnection) : this(
        connection,
        ::SMBClient,
        ::listSmbShares,
    )

    private var client: SMBClient? = null
    private var smbConnection: Connection? = null
    private var session: Session? = null
    /**
     * Cached per-share [DiskShare]. This belongs to the [session], NOT to any single [InputStream]
     * returned by [openStream]. smbj returns the same cached DiskShare for a given share name, so if a
     * stream's close() also closed the share, a concurrent/next stream would read a dead share and get
     * "DiskShare has already been closed". We therefore let this client own the share lifecycle and
     * only close individual files inside the stream.
     */
    private val shares = mutableMapOf<String, DiskShare>()

    private val shareName: String get() = connection.path.trim('/')
    private val isServerRoot: Boolean get() = connection.path == "/"

    override val rootPath: String = ""

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(
                value =
                    isServerRoot || (shareName.isNotEmpty() && !shareName.contains(char = '/')),
            ) { "Path must be / or just the share name (e.g. Media), without any folders." }
            // Disable signing/encryption: avoids Key.getEncoded() crashes on Android.
            val config = SmbConfig.builder()
                .withTimeout(
                    30,
                    TimeUnit.SECONDS,
                )
                .withSoTimeout(
                    35,
                    TimeUnit.SECONDS,
                )
                .withDialects(
                    SMB2Dialect.SMB_3_1_1,
                    SMB2Dialect.SMB_3_0_2,
                    SMB2Dialect.SMB_3_0,
                    SMB2Dialect.SMB_2_1,
                    SMB2Dialect.SMB_2_0_2,
                )
                .withDfsEnabled(false)
                .withMultiProtocolNegotiate(true)
                .withSigningRequired(false)
                .withEncryptData(false)
                // Add large buffer sizes (e.g., 8MB)
                .withReadBufferSize(8 * 1024 * 1024)
                .withWriteBufferSize(8 * 1024 * 1024)
                .build()

            val smbClient = createClient(config)
            client = smbClient
            try {
                val conn = smbClient.connect(
                    connection.host,
                    connection.effectivePort,
                )
                smbConnection = conn
                val authContext = if (connection.isAnonymous) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        connection.username,
                        connection.password.toCharArray(),
                        null,
                    )
                }
                val sess = conn.authenticate(authContext)
                session = sess
                if (isServerRoot) {
                    // Test enumeration as well as authentication before saving a server root.
                    listShares(sess)
                } else {
                    // Probe that the share is a readable disk share, but KEEP it open and cached
                    // (via getShare) so openStream reuses the exact same connected DiskShare.
                    // Closing it here made smbj hand back a stale "already closed" TreeConnect.
                    val share = getShare(name = shareName)
                    runCatching { share.list(smbPath(relative = "")) }
                        .onFailure { throw IllegalStateException("Share '$shareName' is not readable", it) }
                }
            } catch (error: Throwable) {
                closeResources()
                throw error
            }
            Unit
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) { closeResources() }

    private fun closeResources() {
        shares.values.forEach { runCatching { it.close() } }
        shares.clear()
        runCatching { session?.close() }
        runCatching { smbConnection?.close() }
        runCatching { client?.close() }
        session = null
        smbConnection = null
        client = null
    }

    /**
     * Returns the cached [DiskShare] for [name], creating and caching it on first use.
     *
     * The share is owned by this client and closed only by [closeResources] — never per operation —
     * so the connect-time probe, every [openStream], [listFiles] and [fileSize] all reuse the same
     * live connection.
     *
     * Why this matters: smbj caches a TreeConnect per share name. The old code did
     * `connectShare(name).use { list("") }` during [connect], which closed the share, and then the
     * next `connectShare` returned that *stale, already-closed* cached object — causing
     * "DiskShare has already been closed" on the very first open. Keeping the share open and reusing
     * our own cache sidesteps that entirely.
     */
    private fun getShare(name: String): DiskShare {
        shares[name]?.let { return it }
        val sess = session ?: error(message = "Not connected")
        val disk = (sess.connectShare(name) as? DiskShare)
            ?: error(message = "Share '$name' is not a disk share")
        shares[name] = disk
        return disk
    }

    override fun isConnected(): Boolean = session != null && smbConnection?.isConnected == true

    override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val relative = path.trim('/')
                if (isServerRoot && relative.isEmpty()) {
                    return@runCatching listShares(session ?: error(message = "Not connected"))
                }
                val (selectedShare, sharePath) = shareAndPath(path = path)
                val share = getShare(name = selectedShare)
                share.list(smbPath(relative = sharePath)).mapNotNull { info ->
                    val name = info.fileName
                    if (name == "." || name == ".." ||
                        name.endsWith(suffix = "$")
                    ) {
                        return@mapNotNull null
                    }
                    // FILE_ATTRIBUTE_DIRECTORY
                    val isDirectory = info.fileAttributes and 0x10L != 0L
                    NetworkFile(
                        name = name,
                        path = if (relative.isEmpty()) name else "$relative/$name",
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0 else info.endOfFile,
                        modified = info.lastWriteTime?.toEpochMillis(),
                    )
                }
            }
        }

    override suspend fun fileSize(path: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            val (selectedShare, sharePath) = shareAndPath(path = path)
            val share = getShare(name = selectedShare)
            openReadFile(
                share = share,
                path = sharePath,
            ).use {
                it.fileInformation.standardInformation.endOfFile
            }
        }.getOrDefault(defaultValue = -1L)
    }

    override suspend fun openStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            if (!isConnected()) connect().getOrThrow()
            val (selectedShare, sharePath) = shareAndPath(path = path)
            // Reuse the cached DiskShare for this share name. It is owned by the session and must
            // outlive any single stream — only individual files get closed when a stream ends.
            val share = getShare(name = selectedShare)
            val file = openReadFile(
                share = share,
                path = sharePath,
            )

            val rawStream = object : InputStream() {
                private var position = offset

                override fun read(): Int {
                    val one = ByteArray(size = 1)
                    return if (read(
                            one,
                            0,
                            1,
                        ) == -1
                    ) {
                        -1
                    } else {
                        one[0].toInt() and 0xFF
                    }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = file.read(
                        b,
                        position,
                        off,
                        len,
                    )
                    if (read > 0) position += read
                    return read
                }

                override fun close() {
                    // Only close the file. The DiskShare is shared/session-owned and is closed by
                    // closeResources() on disconnect/reconnect, never by an individual stream.
                    runCatching { file.close() }
                }
            }

            // Wrap the stream in a 2MB buffer to drastically reduce SMB network requests
            return@withContext rawStream.buffered(bufferSize = 2 * 1024 * 1024)
        }

    private fun openReadFile(share: DiskShare, path: String) = share.openFile(
        smbPath(path.trim('/')),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
        SMB2CreateDisposition.FILE_OPEN,
        null,
    )

    private fun smbPath(relative: String): String = relative.replace(
        oldChar = '/',
        newChar = '\\',
    )

    private fun shareAndPath(path: String): Pair<String, String> {
        val relative = path.trim('/')
        if (!isServerRoot) return shareName to relative
        require(relative.isNotEmpty()) { "Select a share before opening a file." }
        return relative.substringBefore(delimiter = '/') to
            relative.substringAfter(
                delimiter = '/',
                missingDelimiterValue = "",
            )
    }
}

private fun listSmbShares(session: Session): List<NetworkFile> =
    (session.connectShare("IPC$") as PipeShare).use { share ->
        NamedPipe(
            session,
            share,
            "srvsvc",
        ).use { pipe ->
            val transport = SMBTransport(pipe)
            transport.bind(
                Interface.SRVSVC_V3_0,
                Interface.NDR_32BIT_V2,
            )
            ServerService(transport).shares1
                .filter { it.type and 0xFFFF == 0 && !it.netName.endsWith(suffix = "$") }
                .map {
                    NetworkFile(
                        name = it.netName,
                        path = it.netName,
                        isDirectory = true,
                    )
                }
        }
    }
