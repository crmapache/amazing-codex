package io.github.crmapache.amazingcodex.remote

import com.intellij.openapi.components.State
import com.intellij.util.ThreeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The headstone, which is the one part of this record that has to be right about the past.
 *
 * A device that was let go of has to be TOLD so when it comes back - the silence it used to meet is
 * the same silence a switched-off machine makes, and a person cannot tell those apart. That answer is
 * only allowed for an address this agent itself let go of, so what this list remembers, and what it
 * stops remembering, is the whole of the rule.
 */
class RemoteStateTest {

    private fun device(id: String) = RemoteState.Device().apply { this.id = id }

    @Test
    fun `a device that was let go of is remembered as such`() {
        val state = RemoteState()
        state.remember(device("phone"))

        state.forget("phone")
        state.noteRevoked("phone")

        assertTrue(state.wasRevoked("phone"))
        assertTrue(state.devices().isEmpty())
    }

    /** An address nobody here ever paired with learns nothing - that is what keeps the answer honest. */
    @Test
    fun `a stranger is not on the list`() {
        assertFalse(RemoteState().wasRevoked("somebody-else"))
    }

    /**
     * Bounded, because it grows by a row per revocation and is worth nothing after a while: a device
     * let go of a year and thirty pairings ago meets the old silence, as it always did.
     */
    @Test
    fun `only the last few are kept`() {
        val state = RemoteState()
        repeat(RemoteState.REVOKED_REMEMBERED + 5) { state.noteRevoked("device-$it") }

        assertFalse(state.wasRevoked("device-0"))
        assertTrue(state.wasRevoked("device-${RemoteState.REVOKED_REMEMBERED + 4}"))
    }

    /** The list a person revokes from is the list a resuming device is checked against (RemoteAgent.sessionInit). */
    @Test
    fun `only a remembered device is listed`() {
        val state = RemoteState()
        state.remember(device("phone"))

        assertTrue(state.lists("phone"))
        assertFalse(state.lists("tablet"))
    }

    /**
     * Saved when asked, not once in five minutes. A non-roaming storage gets the platform's save threshold
     * unless it opts out, and with it the save after a pairing wrote nothing: an IDE stopped soon after came
     * back without the record while the key stayed in the keychain - a phone that connected and was listed
     * nowhere.
     */
    @Test
    fun `the record is saved without the platform's threshold`() {
        val storage = RemoteState::class.java.getAnnotation(State::class.java).storages.single()

        assertEquals(ThreeState.NO, storage.useSaveThreshold)
    }

    /** Revoking the same address twice must not spend two of those rows. */
    @Test
    fun `the same address is remembered once`() {
        val state = RemoteState()
        repeat(3) { state.noteRevoked("phone") }
        state.noteRevoked("tablet")

        assertEquals(listOf("phone", "tablet"), state.state.revoked.toList())
    }

    /**
     * A new identity is a new agent as far as every device is concerned, and a headstone under a name
     * nobody holds any more marks nobody's grave.
     */
    @Test
    fun `a fresh identity carries nothing over`() {
        val state = RemoteState()
        state.noteRevoked("phone")

        state.resetIdentity()

        assertFalse(state.wasRevoked("phone"))
    }
}
