package io.r2h.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineConnectionOwnerTest {
    private class FakeBinding : EngineBindingAdapter<String> {
        var bindCalls = 0
        var unbindCalls = 0
        var listener: EngineBindingListener<String>? = null

        override fun bind(listener: EngineBindingListener<String>): Boolean {
            bindCalls += 1
            this.listener = listener
            return true
        }

        override fun unbind(listener: EngineBindingListener<String>) {
            unbindCalls += 1
            if (this.listener === listener) this.listener = null
        }

        fun connect(service: String = "service") = listener!!.onConnected(service)
        fun disconnect() = listener!!.onDisconnected()
        fun bindingDied() = listener!!.onBindingDied()
    }

    private class FakeSchedule : EngineConnectionSchedule {
        var refreshSchedules = 0
        var refreshCancels = 0
        var reconnectSchedules = 0
        var reconnectCancels = 0
        var reconnectAction: (() -> Unit)? = null

        override fun replaceRefreshSequence(action: () -> Unit) {
            refreshSchedules += 1
        }

        override fun cancelRefreshSequence() {
            refreshCancels += 1
        }

        override fun scheduleReconnect(action: () -> Unit) {
            if (reconnectAction == null) {
                reconnectSchedules += 1
                reconnectAction = action
            }
        }

        override fun cancelReconnect() {
            reconnectCancels += 1
            reconnectAction = null
        }

        override fun close() {
            cancelRefreshSequence()
            cancelReconnect()
        }

        fun runReconnect() {
            val action = reconnectAction
            reconnectAction = null
            action?.invoke()
        }
    }

    @Test
    fun `repeated lifecycle starts keep one binding`() {
        val binding = FakeBinding()
        val schedule = FakeSchedule()
        val owner = EngineConnectionOwner(binding, schedule)

        owner.ensureConnected()
        owner.ensureConnected()
        owner.ensureConnected()

        assertEquals(1, binding.bindCalls)
        assertEquals(EngineConnectionState.CONNECTING, owner.state)
    }

    @Test
    fun `connected binding owns one refresh sequence`() {
        val binding = FakeBinding()
        val schedule = FakeSchedule()
        val owner = EngineConnectionOwner(binding, schedule)
        owner.ensureConnected()

        binding.connect("engine")
        owner.ensureConnected()

        assertEquals(EngineConnectionState.CONNECTED, owner.state)
        assertEquals("engine", owner.service)
        assertEquals(1, binding.bindCalls)
        assertEquals(1, schedule.refreshSchedules)
    }

    @Test
    fun `binder death schedules one reconnect and replaces the dead binding`() {
        val binding = FakeBinding()
        val schedule = FakeSchedule()
        val disconnectReasons = mutableListOf<EngineDisconnectReason>()
        val owner = EngineConnectionOwner(binding, schedule, onDisconnected = disconnectReasons::add)
        owner.ensureConnected()
        binding.connect("old")

        val deadBindingListener = binding.listener!!
        deadBindingListener.onBindingDied()
        deadBindingListener.onBindingDied()

        assertNull(owner.service)
        assertEquals(1, schedule.reconnectSchedules)
        assertEquals(listOf(EngineDisconnectReason.BINDER_DIED), disconnectReasons)

        schedule.runReconnect()
        assertEquals(2, binding.bindCalls)
        binding.connect("new")
        assertEquals("new", owner.service)
        assertEquals(2, schedule.refreshSchedules)
    }

    @Test
    fun `close explicitly unbinds and prevents reconnect`() {
        val binding = FakeBinding()
        val schedule = FakeSchedule()
        val owner = EngineConnectionOwner(binding, schedule)
        owner.ensureConnected()
        binding.connect()

        owner.close()

        assertEquals(EngineConnectionState.CLOSED, owner.state)
        assertNull(owner.service)
        assertEquals(1, binding.unbindCalls)
        schedule.runReconnect()
        assertEquals(1, binding.bindCalls)
        assertTrue(schedule.refreshCancels > 0)
        assertTrue(schedule.reconnectCancels > 0)
    }
}
