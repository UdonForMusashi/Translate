package dev.translate.installer.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuGateControllerTest {
    @Test
    fun `reports unavailable service without requesting permission`() {
        val fixture = Fixture(FakeGateway(binderAlive = false))

        fixture.controller.start()
        fixture.controller.verifyOrRequestPermission()

        assertEquals(ShizukuGateStatus.SERVICE_UNAVAILABLE, fixture.states.last().status)
        assertTrue(fixture.gateway.requestCodes.isEmpty())
    }

    @Test
    fun `requests permission and approves only after matching granted result`() {
        val fixture = Fixture(
            FakeGateway(
                binderAlive = true,
                permissionGranted = false,
                showRationale = false,
                apiVersion = 13,
            ),
        )

        fixture.controller.start()
        fixture.controller.verifyOrRequestPermission()
        assertEquals(ShizukuGateStatus.PERMISSION_REQUESTING, fixture.states.last().status)
        assertEquals(1, fixture.gateway.requestCodes.size)

        fixture.gateway.permissionGranted = true
        fixture.gateway.callbacks.onPermissionResult(
            requestCode = fixture.gateway.requestCodes.single() + 1,
            granted = true,
        )
        assertEquals(ShizukuGateStatus.PERMISSION_REQUESTING, fixture.states.last().status)

        fixture.gateway.callbacks.onPermissionResult(
            requestCode = fixture.gateway.requestCodes.single(),
            granted = true,
        )
        assertEquals(ShizukuGateStatus.READY, fixture.states.last().status)
        assertEquals(13, fixture.states.last().serverApiVersion)
    }

    @Test
    fun `does not repeat a permission request after denial rationale`() {
        val fixture = Fixture(
            FakeGateway(
                binderAlive = true,
                permissionGranted = false,
                showRationale = true,
            ),
        )

        fixture.controller.start()
        fixture.controller.verifyOrRequestPermission()

        assertEquals(ShizukuGateStatus.PERMISSION_DENIED, fixture.states.last().status)
        assertTrue(fixture.gateway.requestCodes.isEmpty())
    }

    @Test
    fun `keeps waiting when activity resumes during a permission request`() {
        val fixture = Fixture(
            FakeGateway(
                binderAlive = true,
                permissionGranted = false,
                showRationale = false,
            ),
        )

        fixture.controller.start()
        fixture.controller.verifyOrRequestPermission()
        fixture.controller.revalidateAfterForeground()

        assertEquals(ShizukuGateStatus.PERMISSION_REQUESTING, fixture.states.last().status)
        assertEquals(1, fixture.gateway.requestCodes.size)
    }

    @Test
    fun `rejects an outdated Shizuku server API`() {
        val fixture = Fixture(
            FakeGateway(
                binderAlive = true,
                permissionGranted = true,
                apiVersion = 12,
            ),
        )

        fixture.controller.start()
        fixture.controller.verifyOrRequestPermission()

        assertEquals(ShizukuGateStatus.VERSION_UNSUPPORTED, fixture.states.last().status)
        assertEquals(12, fixture.states.last().serverApiVersion)
        assertFalse(fixture.states.last().isApproved)
    }

    @Test
    fun `rejects a service that is neither shell nor root`() {
        val fixture = Fixture(
            FakeGateway(
                binderAlive = true,
                permissionGranted = true,
                apiVersion = 13,
                uid = 12_345,
            ),
        )

        fixture.controller.start()
        fixture.controller.verifyOrRequestPermission()

        assertEquals(ShizukuGateStatus.IDENTITY_UNTRUSTED, fixture.states.last().status)
        assertFalse(fixture.states.last().isApproved)
    }

    @Test
    fun `revokes approval immediately when the binder dies`() {
        val fixture = Fixture(
            FakeGateway(
                binderAlive = true,
                permissionGranted = true,
                apiVersion = 13,
            ),
        )

        fixture.controller.start()
        fixture.controller.verifyOrRequestPermission()
        assertTrue(fixture.states.last().isApproved)

        fixture.gateway.callbacks.onBinderDead()

        assertEquals(ShizukuGateStatus.SERVICE_UNAVAILABLE, fixture.states.last().status)
        assertFalse(fixture.states.last().isApproved)
    }

    private class Fixture(
        val gateway: FakeGateway,
    ) {
        val states = mutableListOf<ShizukuGateState>()
        val controller = ShizukuGateController(gateway, states::add)
    }

    private class FakeGateway(
        var binderAlive: Boolean,
        var permissionGranted: Boolean = false,
        var showRationale: Boolean = false,
        var apiVersion: Int = 13,
        var uid: Int = 2_000,
    ) : ShizukuGateway {
        lateinit var callbacks: ShizukuGatewayCallbacks
        val requestCodes = mutableListOf<Int>()

        override fun start(callbacks: ShizukuGatewayCallbacks) {
            this.callbacks = callbacks
        }

        override fun stop() = Unit

        override fun isBinderAlive(): Boolean = binderAlive

        override fun hasPermission(): Boolean = permissionGranted

        override fun shouldShowPermissionRationale(): Boolean = showRationale

        override fun requestPermission(requestCode: Int) {
            requestCodes += requestCode
        }

        override fun serverApiVersion(): Int = apiVersion

        override fun serverUid(): Int = uid
    }
}
