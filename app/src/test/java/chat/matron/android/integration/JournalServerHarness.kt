package chat.matron.android.integration

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.AssumptionViolatedException

/// Boots a real `matron-journal` server (Node) as a subprocess for integration
/// tests, provisions users/agents via the admin CLI, and tears everything down
/// (process + temp DB directory) on [stop]. Faithful port of the matron-apple
/// `JournalServerHarness` (`Process` → `ProcessBuilder`, `URLSession` probe →
/// synchronous OkHttp).
///
/// Server checkout resolves via `MATRON_JOURNAL_PATH` first, then falls back to
/// `~/Dev/matron-journal`. A missing checkout / node / node_modules throws an
/// [AssumptionViolatedException] (JUnit reports the test as *skipped*, keeping
/// CI green where there is no checkout — the analogue of the Apple suite's
/// `XCTSkip`). Any other startup failure (server crashed, never became ready)
/// is a real error and fails the test rather than skipping it.
class JournalServerHarness private constructor(
    val baseURL: HttpUrl,
    private val process: Process,
    private val tempDir: File,
    private val dbPath: String,
    private val serverPath: String,
    private val nodePath: String,
    val agentTokens: Map<String, String>,
    private val diagnostics: DiagnosticsBuffer,
) {
    data class UserSpec(val name: String, val password: String)

    data class AgentSpec(val user: String, val name: String)

    /// Non-skip startup failures (a real error — must fail the test).
    class HarnessException(message: String) : Exception(message)

    /// Terminates the server subprocess and deletes the temp DB directory. The
    /// watchdog shell's `TERM` trap forwards the signal to the node child.
    fun stop() {
        process.destroy()
        process.waitFor(10, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
        tempDir.deleteRecursively()
    }

    companion object {
        /// Locates the checkout + node, provisions `users`/`agents` via the
        /// admin CLI (BEFORE the server boots), boots `node src/server.js` on a
        /// free port, and polls `GET /snapshot` (expects 401) until ready.
        fun start(
            users: List<UserSpec> = emptyList(),
            agents: List<AgentSpec> = emptyList(),
        ): JournalServerHarness {
            val serverPath = requireServerRepo()
            val nodePath = resolveNodePath()

            // tempDir is only created once the skip-worthy preconditions above
            // have passed — a skip must never leave a directory behind. Every
            // step below runs under a single cleanup block: any failure
            // terminates a booted server and removes tempDir before rethrowing,
            // so a failed start() never leaks a temp DB directory or subprocess.
            val tempDir = File.createTempFile("matron-journal-test-", "").let { file ->
                file.delete()
                file.mkdirs()
                file
            }
            val dbPath = File(tempDir, "matron.sqlite").absolutePath

            var bootedProcess: Process? = null
            try {
                for (user in users) {
                    runAdminCLI(nodePath, serverPath, dbPath,
                        listOf("user", "add", user.name, "--password", user.password))
                }
                val tokens = mutableMapOf<String, String>()
                for (agent in agents) {
                    val output = runAdminCLI(nodePath, serverPath, dbPath,
                        listOf("agent", "add", agent.user, agent.name))
                    tokens[agent.name] = parseAgentToken(output, agent.name)
                }

                val port = findFreePort()
                val (process, diagnostics) = bootServer(nodePath, serverPath, dbPath, port)
                bootedProcess = process
                val baseURL = "http://127.0.0.1:$port".toHttpUrl()

                waitForReadiness(baseURL, diagnostics)

                return JournalServerHarness(
                    baseURL, process, tempDir, dbPath, serverPath, nodePath,
                    tokens.toMap(), diagnostics,
                )
            } catch (t: Throwable) {
                bootedProcess?.let {
                    it.destroy()
                    it.waitFor(5, TimeUnit.SECONDS)
                    if (it.isAlive) it.destroyForcibly()
                }
                tempDir.deleteRecursively()
                throw t
            }
        }

        // MARK: Repo / node discovery

        private fun skip(message: String): Nothing = throw AssumptionViolatedException(message)

        private fun requireServerRepo(): String {
            val path = System.getenv("MATRON_JOURNAL_PATH")
                ?: (System.getProperty("user.home") + "/Dev/matron-journal")
            if (!File(path, "src/server.js").exists()) {
                skip("matron-journal checkout not found at $path — set MATRON_JOURNAL_PATH to override")
            }
            if (!File(path, "node_modules").isDirectory) {
                skip("matron-journal node_modules missing at $path — run `npm install` there once")
            }
            return path
        }

        /// Resolves node once per test process (fork/exec + up-to-10s shell wait
        /// is paid at most once). Honors `MATRON_NODE_PATH`, then the current
        /// `PATH`, then `/bin/zsh -i -c 'command -v node'` (which sources
        /// `.zshrc`, so nvm's version-specific node is found even when the JVM's
        /// own `PATH` doesn't carry it), then common install locations.
        private val cachedNodePath = AtomicReference<Result<String>?>(null)
        private val nodeLock = Any()

        private fun resolveNodePath(): String = synchronized(nodeLock) {
            cachedNodePath.get()?.let { return it.getOrThrow() }
            val result = runCatching { resolveNodePathUncached() }
            cachedNodePath.set(result)
            result.getOrThrow()
        }

        private fun resolveNodePathUncached(): String {
            System.getenv("MATRON_NODE_PATH")?.let { overridden ->
                if (File(overridden).canExecute()) return overridden
            }
            resolveViaPath()?.let { return it }
            resolveViaInteractiveShell(timeoutMs = 10_000)?.let { return it }
            for (candidate in listOf("/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node")) {
                if (File(candidate).canExecute()) return candidate
            }
            skip(
                "node not resolvable via PATH, `zsh -i -c 'command -v node'` (timed out or " +
                    "failed), MATRON_NODE_PATH, or common install paths — set MATRON_NODE_PATH to override"
            )
        }

        private fun resolveViaPath(): String? {
            val path = System.getenv("PATH") ?: return null
            for (dir in path.split(File.pathSeparatorChar)) {
                if (dir.isEmpty()) continue
                val candidate = File(dir, "node")
                if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            }
            return null
        }

        /// Returns `null` (never throws) on timeout or any resolution failure —
        /// callers fall through to the next link in the chain. The interactive
        /// shell is a subprocess of unknown provenance (a hung `.zshrc` must not
        /// wedge the job), so it is bounded and force-killed on timeout.
        private fun resolveViaInteractiveShell(timeoutMs: Long): String? = try {
            val pb = ProcessBuilder("/bin/zsh", "-i", "-c", "command -v node")
            // Deliberately minimal + explicit rather than inherited: this must
            // work regardless of the JVM's own PATH/HOME.
            pb.environment().clear()
            pb.environment()["HOME"] = System.getProperty("user.home")
            val proc = pb.start()
            val outBuf = StringBuilder()
            val outReader = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine {
                        synchronized(outBuf) { outBuf.append(it).append('\n') }
                    }
                }
            }.apply { isDaemon = true; start() }
            val errReader = Thread { runCatching { proc.errorStream.readBytes() } }
                .apply { isDaemon = true; start() }
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                null
            } else {
                outReader.join(500)
                errReader.join(500)
                val path = synchronized(outBuf) { outBuf.toString() }.trim()
                if (proc.exitValue() == 0 && path.isNotEmpty() && File(path).canExecute()) path else null
            }
        } catch (e: Exception) {
            null
        }

        // MARK: Admin CLI

        private fun runAdminCLI(
            nodePath: String, serverPath: String, dbPath: String, arguments: List<String>,
        ): String {
            val pb = ProcessBuilder(listOf(nodePath, "bin/matron-admin.js") + arguments)
            pb.directory(File(serverPath))
            pb.environment()["MATRON_DB"] = dbPath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            proc.waitFor()
            if (proc.exitValue() != 0) {
                throw HarnessException("matron-admin ${arguments.joinToString(" ")} failed:\n$output")
            }
            return output
        }

        // Matches "agent <name> token: <64hex>" — bin/matron-admin.js's exact
        // format (printed once, so this is the only place the value exists).
        private val tokenRegex = Regex("""token:\s*([0-9a-f]{64})""")

        private fun parseAgentToken(output: String, agentName: String): String =
            tokenRegex.find(output)?.groupValues?.get(1)
                ?: throw HarnessException("could not parse token for agent $agentName from admin CLI output:\n$output")

        // MARK: Free port

        private fun findFreePort(): Int =
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("127.0.0.1", 0))
                socket.localPort
            }

        // MARK: Server boot

        /// Boots `node src/server.js` wrapped in a small `/bin/sh` watchdog
        /// rather than as a direct child, so the node process can't outlive this
        /// harness even if the JVM is SIGKILLed (a signal [stop] never gets to
        /// run for). The `trap` handles the normal [stop] path (SIGTERM to the
        /// shell forwards a `kill` to node); the `kill -0 "$PPID"` loop handles
        /// the SIGKILL case (once the original parent is gone, the loop kills the
        /// orphaned node child within ~1s).
        private fun bootServer(
            nodePath: String, serverPath: String, dbPath: String, port: Int,
        ): Pair<Process, DiagnosticsBuffer> {
            val watchdogScript = """
                trap 'kill "${'$'}SERVER" 2>/dev/null; wait "${'$'}SERVER" 2>/dev/null; exit 0' TERM INT
                "$nodePath" src/server.js &
                SERVER=${'$'}!
                while kill -0 "${'$'}PPID" 2>/dev/null; do
                    sleep 1
                done
                kill "${'$'}SERVER" 2>/dev/null
                wait "${'$'}SERVER" 2>/dev/null
            """.trimIndent()

            val pb = ProcessBuilder("/bin/sh", "-c", watchdogScript)
            pb.directory(File(serverPath))
            pb.environment().apply {
                put("MATRON_DB", dbPath)
                put("MATRON_PORT", port.toString())
                put("MATRON_BIND", "127.0.0.1")
            }
            pb.redirectErrorStream(true)
            val process = pb.start()

            val diagnostics = DiagnosticsBuffer()
            // Drain the merged output continuously so a full pipe buffer can
            // never block node, and so failure diagnostics are available.
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { diagnostics.append(it + "\n") }
                }
            }.apply { isDaemon = true; start() }

            return process to diagnostics
        }

        /// Polls `GET /snapshot` (expects 401 — proof the HTTP layer is up and
        /// enforcing auth) until it succeeds [consecutiveSuccessesRequired] times
        /// in a row, each on a brand-new, non-pooled connection. A single success
        /// is not enough: a Node server whose `listen()` callback just fired can
        /// answer one connection and reset the next moments later, so a few
        /// clean-in-a-row probes ride out that window.
        private fun waitForReadiness(
            baseURL: HttpUrl,
            diagnostics: DiagnosticsBuffer,
            timeoutMs: Long = 5_000,
            consecutiveSuccessesRequired: Int = 3,
        ) {
            val deadline = System.currentTimeMillis() + timeoutMs
            val client = OkHttpClient.Builder()
                // Zero-keepalive pool: every probe is forced onto its own socket
                // (no connection reuse masking the accept-queue settle window).
                .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                .build()
            val url = baseURL.newBuilder().addPathSegment("snapshot").build()
            var lastError: String? = null
            var consecutiveSuccesses = 0
            while (System.currentTimeMillis() < deadline) {
                val got401 = try {
                    val request = Request.Builder().url(url).header("Connection", "close").build()
                    client.newCall(request).execute().use { response ->
                        if (response.code != 401) lastError = "unexpected status ${response.code}"
                        response.code == 401
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: e.toString()
                    false
                }
                if (got401) {
                    consecutiveSuccesses += 1
                    if (consecutiveSuccesses >= consecutiveSuccessesRequired) return
                    Thread.sleep(30)
                } else {
                    consecutiveSuccesses = 0
                    Thread.sleep(100)
                }
            }
            throw HarnessException(
                "matron-journal server did not become ready (last error: $lastError):\n${diagnostics.contents}"
            )
        }
    }
}

/// Thread-safe accumulator for the server subprocess's merged stdout/stderr,
/// surfaced only when the harness fails to become ready.
class DiagnosticsBuffer {
    private val lock = Any()
    private val buffer = StringBuilder()

    fun append(text: String) {
        synchronized(lock) { buffer.append(text) }
    }

    val contents: String get() = synchronized(lock) { buffer.toString() }
}
