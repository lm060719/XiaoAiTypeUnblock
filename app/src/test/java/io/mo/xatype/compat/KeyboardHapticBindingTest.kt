package io.mo.xatype.compat

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class KeyboardHapticBindingTest {
    enum class Event { KEY_PRESS, LONG_PRESS, ERROR, SUCCESS, BUTTON_LARGE }
    enum class Unrelated { CLICK }
    class Scope

    @Suppress("UNUSED_PARAMETER")
    class Manager(val context: Context?) {
        var vibrationEnabled = true
        var intensity = 50
        var lastScope: Scope? = null
        val feedback = mutableListOf<Pair<Event, Int>>()

        fun dispatch(event: Event, scope: Scope) {
            lastScope = scope
            if (vibrationEnabled) feedback.add(event to intensity)
        }

        fun unrelated(value: Int, scope: Any) {}
    }
    class Service {
        val manager = Manager(null)
        val scope = Scope()
        fun `getHapticFeedbackManager$test`() = manager
        fun `getServiceScope$test`() = scope
    }
    @Suppress("UNUSED_PARAMETER")
    class AmbiguousManager(val context: Context?) {
        fun first(event: Event, scope: Any) {}
        fun second(event: Event, scope: Any) {}
    }
    class AmbiguousService {
        fun `getHapticFeedbackManager$test`() = AmbiguousManager(null)
    }
    @Suppress("UNUSED_PARAMETER")
    class UnrelatedManager(val context: Context?) {
        fun dispatch(event: Unrelated, scope: Any) {}
    }
    class UnrelatedService {
        fun `getHapticFeedbackManager$test`() = UnrelatedManager(null)
    }

    @Test fun findsTheNamedManagerAndIgnoresUnrelatedVoidMethods() {
        assertEquals("dispatch", KeyboardHapticBinding(Service::class.java).dispatch.name)
    }
    @Test fun playbackUsesTheLiveManagerAndScopeForEachKeyPress() {
        val service = Service()
        val play = KeyboardHapticBinding(Service::class.java).keyPressFeedback(service)
        play()
        service.manager.intensity = 150
        play()
        service.manager.vibrationEnabled = false
        play()
        assertSame(service.scope, service.manager.lastScope)
        assertEquals(listOf(Event.KEY_PRESS to 50, Event.KEY_PRESS to 150), service.manager.feedback)
    }
    @Test fun ambiguousDispatchersFailWithoutGuessing() {
        assertTrue(runCatching { KeyboardHapticBinding(AmbiguousService::class.java) }.isFailure)
    }
    @Test fun unrelatedEventsFailWithoutGuessing() {
        assertTrue(runCatching { KeyboardHapticBinding(UnrelatedService::class.java) }.isFailure)
    }
}
