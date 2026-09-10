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
        var stoppedSharing = false
        actions.register(owner, { hungUp = true }, { muted = true }, { stoppedSharing = true })
        assertTrue(actions.hangUp())
        assertTrue(actions.toggleMute())
        assertTrue(actions.stopSharing())
        assertTrue(hungUp)
        assertTrue(muted)
        assertTrue(stoppedSharing)
        actions.clear(owner)
        assertFalse(actions.hangUp())
        // A notification outliving its call must not reach a later one either.
        assertFalse(actions.stopSharing())
    }

    @Test fun staleOwnerCannotClearReplacement() {
        val actions = ActiveCallActions()
        val old = Any()
        val current = Any()
        var called = false
        actions.register(old, {}, {}, {})
        actions.register(current, { called = true }, {}, {})
        actions.clear(old)
        assertTrue(actions.hangUp())
        assertTrue(called)
    }
}
