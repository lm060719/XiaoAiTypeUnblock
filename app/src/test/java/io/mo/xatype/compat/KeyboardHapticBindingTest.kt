package io.mo.xatype.compat

import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class KeyboardHapticBindingTest {
    enum class Event { KEY_PRESS, LONG_PRESS, ERROR, SUCCESS, BUTTON_LARGE }
    enum class Unrelated { CLICK }

    @Suppress("UNUSED_PARAMETER")
    class Manager(val context: Context?) {
        fun dispatch(event: Event, scope: Any) {}
        fun unrelated(value: Int, scope: Any) {}
    }
    class Service {
        fun `getHapticFeedbackManager$test`() = Manager(null)
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
    @Test fun ambiguousDispatchersFailWithoutGuessing() {
        assertTrue(runCatching { KeyboardHapticBinding(AmbiguousService::class.java) }.isFailure)
    }
    @Test fun unrelatedEventsFailWithoutGuessing() {
        assertTrue(runCatching { KeyboardHapticBinding(UnrelatedService::class.java) }.isFailure)
    }
}
