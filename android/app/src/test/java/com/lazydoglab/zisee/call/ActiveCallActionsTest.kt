package com.lazydoglab.zisee.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCallActionsTest {
    @Test fun currentOwnerReceivesNotificationActions() {
        val actions = ActiveCallActions()
        val owner = Any()
        var hungUp = false
        var muted = false
        actions.register(owner, { hungUp = true }, { muted = true })
        assertTrue(actions.hangUp())
        assertTrue(actions.toggleMute())
        assertTrue(hungUp)
        assertTrue(muted)
        actions.clear(owner)
        assertFalse(actions.hangUp())
    }

    @Test fun staleOwnerCannotClearReplacement() {
        val actions = ActiveCallActions()
        val old = Any()
        val current = Any()
        var called = false
        actions.register(old, {}, {})
        actions.register(current, { called = true }, {})
        actions.clear(old)
        assertTrue(actions.hangUp())
        assertTrue(called)
    }
}
