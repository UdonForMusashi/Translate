package dev.translate.installer.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class ShizukuGateStatus {
    CHECK_REQUIRED,
    CHECKING,
    SERVICE_UNAVAILABLE,
    PERMISSION_REQUIRED,
    PERMISSION_REQUESTING,
    PERMISSION_DENIED,
    VERSION_UNSUPPORTED,
    IDENTITY_UNTRUSTED,
    READY,
    ERROR,
}

enum class ShizukuServiceIdentity {
    SHELL,
    ROOT,
}

data class ShizukuGateState(
    val status: ShizukuGateStatus = ShizukuGateStatus.CHECK_REQUIRED,
    val serverApiVersion: Int? = null,
    val serviceIdentity: ShizukuServiceIdentity? = null,
) {
    val isApproved: Boolean
        get() = status == ShizukuGateStatus.READY
}

interface ShizukuGatewayCallbacks {
    fun onBinderReceived()
    fun onBinderDead()
    fun onPermissionResult(requestCode: Int, granted: Boolean)
}

interface ShizukuGateway {
    fun start(callbacks: ShizukuGatewayCallbacks)
    fun stop()
    fun isBinderAlive(): Boolean
    fun hasPermission(): Boolean
    fun shouldShowPermissionRationale(): Boolean
    fun requestPermission(requestCode: Int)
    fun serverApiVersion(): Int
    fun serverUid(): Int
}

class AndroidShizukuGateway : ShizukuGateway {
    private var callbacks: ShizukuGatewayCallbacks? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        callbacks?.onBinderReceived()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        callbacks?.onBinderDead()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            callbacks?.onPermissionResult(
                requestCode = requestCode,
                granted = grantResult == PackageManager.PERMISSION_GRANTED,
            )
        }

    override fun start(callbacks: ShizukuGatewayCallbacks) {
        check(this.callbacks == null) { "Shizuku gateway already started" }
        this.callbacks = callbacks
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
    }

    override fun stop() {
        callbacks = null
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
        } catch (_: RuntimeException) {
        }
        try {
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: RuntimeException) {
        }
        try {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: RuntimeException) {
        }
    }

    override fun isBinderAlive(): Boolean = Shizuku.pingBinder()

    override fun hasPermission(): Boolean =
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    override fun shouldShowPermissionRationale(): Boolean =
        Shizuku.shouldShowRequestPermissionRationale()

    override fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    override fun serverApiVersion(): Int = Shizuku.getVersion()

    override fun serverUid(): Int = Shizuku.getUid()
}

class ShizukuGateController(
    private val gateway: ShizukuGateway,
    private val onStateChanged: (ShizukuGateState) -> Unit,
    private val minimumServerApi: Int = MINIMUM_SERVER_API,
) {
    private var started = false
    private var state = ShizukuGateState()

    private val callbacks = object : ShizukuGatewayCallbacks {
        override fun onBinderReceived() {
            when (state.status) {
                ShizukuGateStatus.CHECKING,
                -> probe(allowPermissionRequest = false, announceChecking = true)

                ShizukuGateStatus.PERMISSION_REQUESTING -> Unit

                ShizukuGateStatus.READY ->
                    probe(allowPermissionRequest = false, announceChecking = false)

                else -> publish(ShizukuGateState(ShizukuGateStatus.CHECK_REQUIRED))
            }
        }

        override fun onBinderDead() {
            publish(ShizukuGateState(ShizukuGateStatus.SERVICE_UNAVAILABLE))
        }

        override fun onPermissionResult(requestCode: Int, granted: Boolean) {
            if (requestCode != PERMISSION_REQUEST_CODE ||
                state.status != ShizukuGateStatus.PERMISSION_REQUESTING
            ) {
                return
            }
            if (granted) {
                probe(allowPermissionRequest = false, announceChecking = true)
            } else {
                publish(ShizukuGateState(ShizukuGateStatus.PERMISSION_DENIED))
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        try {
            gateway.start(callbacks)
        } catch (_: RuntimeException) {
            publish(ShizukuGateState(ShizukuGateStatus.ERROR))
        }
    }

    fun verifyOrRequestPermission() {
        if (!started ||
            state.status == ShizukuGateStatus.CHECKING ||
            state.status == ShizukuGateStatus.PERMISSION_REQUESTING ||
            state.status == ShizukuGateStatus.READY
        ) {
            return
        }
        probe(allowPermissionRequest = true, announceChecking = true)
    }

    fun revalidateAfterForeground() {
        if (!started ||
            state.status == ShizukuGateStatus.CHECK_REQUIRED ||
            state.status == ShizukuGateStatus.CHECKING ||
            state.status == ShizukuGateStatus.PERMISSION_REQUESTING
        ) {
            return
        }
        probe(allowPermissionRequest = false, announceChecking = false)
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            gateway.stop()
        } catch (_: RuntimeException) {
        }
    }

    private fun probe(
        allowPermissionRequest: Boolean,
        announceChecking: Boolean,
    ) {
        if (announceChecking) {
            publish(ShizukuGateState(ShizukuGateStatus.CHECKING))
        }
        try {
            if (!gateway.isBinderAlive()) {
                publish(ShizukuGateState(ShizukuGateStatus.SERVICE_UNAVAILABLE))
                return
            }
            if (!gateway.hasPermission()) {
                if (!allowPermissionRequest) {
                    val status = if (gateway.shouldShowPermissionRationale()) {
                        ShizukuGateStatus.PERMISSION_DENIED
                    } else {
                        ShizukuGateStatus.PERMISSION_REQUIRED
                    }
                    publish(ShizukuGateState(status))
                } else if (gateway.shouldShowPermissionRationale()) {
                    publish(ShizukuGateState(ShizukuGateStatus.PERMISSION_DENIED))
                } else {
                    publish(ShizukuGateState(ShizukuGateStatus.PERMISSION_REQUESTING))
                    gateway.requestPermission(PERMISSION_REQUEST_CODE)
                }
                return
            }

            val serverApi = gateway.serverApiVersion()
            if (serverApi < minimumServerApi) {
                publish(
                    ShizukuGateState(
                        status = ShizukuGateStatus.VERSION_UNSUPPORTED,
                        serverApiVersion = serverApi.takeIf { it >= 0 },
                    ),
                )
                return
            }
            val identity = when (gateway.serverUid()) {
                ROOT_UID -> ShizukuServiceIdentity.ROOT
                SHELL_UID -> ShizukuServiceIdentity.SHELL
                else -> {
                    publish(ShizukuGateState(ShizukuGateStatus.IDENTITY_UNTRUSTED))
                    return
                }
            }
            if (!gateway.isBinderAlive()) {
                publish(ShizukuGateState(ShizukuGateStatus.SERVICE_UNAVAILABLE))
                return
            }
            publish(
                ShizukuGateState(
                    status = ShizukuGateStatus.READY,
                    serverApiVersion = serverApi,
                    serviceIdentity = identity,
                ),
            )
        } catch (_: SecurityException) {
            publish(ShizukuGateState(ShizukuGateStatus.PERMISSION_DENIED))
        } catch (_: RuntimeException) {
            publish(ShizukuGateState(ShizukuGateStatus.ERROR))
        }
    }

    private fun publish(newState: ShizukuGateState) {
        state = newState
        onStateChanged(newState)
    }

    companion object {
        const val MINIMUM_SERVER_API = 13
        private const val ROOT_UID = 0
        private const val SHELL_UID = 2_000
        private const val PERMISSION_REQUEST_CODE = 7_341
    }
}
