package com.nuvio.tv.core.livetv

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AiBroadcastNetwork] covering built-in station lineup, deterministic
 * wall-clock EPG slot progression, enable/disable zero-overhead toggle, and DJ speech helpers.
 */
class AiBroadcastNetworkTest {

    private lateinit var context: Context
    private lateinit var prefsStore: MutableMap<String, Any?>
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefsStore = mutableMapOf()
        prefs = InMemorySharedPreferences(prefsStore)
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
    }

    @Test
    fun `builtInChannels contains 6 stations numbered 9000 through 9005`() {
        val channels = AiBroadcastNetwork.builtInChannels
        assertEquals(6, channels.size)
        assertEquals(
            listOf("9000", "9001", "9002", "9003", "9004", "9005"),
            channels.map { it.number }.sorted()
        )
    }

    @Test
    fun `buildScheduleForChannel produces contiguous EPG slots with slot 0 on air`() {
        val config = AiBroadcastNetwork.builtInChannels.first()
        val nowMs = 1_700_000_000_000L
        val slots = AiBroadcastNetwork.buildScheduleForChannel(config, nowMs = nowMs, slotCount = 5)

        assertEquals(5, slots.size)
        assertTrue(slots[0].isOnAirNow)
        assertTrue(slots[0].startTimestampMs <= nowMs)
        assertTrue(slots[0].endTimestampMs > nowMs)

        for (i in 1 until slots.size) {
            assertFalse(slots[i].isOnAirNow)
            assertEquals(0L, slots[i].elapsedMs)
        }

        for (i in 0 until slots.size - 1) {
            assertEquals(slots[i].endTimestampMs, slots[i + 1].startTimestampMs)
        }
    }

    @Test
    fun `toLiveTvChannels returns empty list when disabled in preferences`() {
        prefsStore["ai_broadcast_enabled"] = false

        val result = AiBroadcastNetwork.toLiveTvChannels(context)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `toLiveTvChannels returns all built-in AI channels when enabled`() {
        prefsStore["ai_broadcast_enabled"] = true

        val result = AiBroadcastNetwork.toLiveTvChannels(context)

        assertEquals(6, result.size)
        result.forEach { channel ->
            assertEquals(LiveTvCategory.AI_NETWORK, channel.category)
            assertTrue(channel.isAiNetwork)
            assertTrue(AiBroadcastNetwork.isAiBroadcastUrl(channel.streamUrl))
        }
    }

    @Test
    fun `extractChannelId parses id from synthetic ai-channel stream url`() {
        val channel = AiBroadcastNetwork.builtInChannels.first()
        val streamUrl = AiBroadcastNetwork.STREAM_URL_PREFIX + channel.id

        assertTrue(AiBroadcastNetwork.isAiBroadcastUrl(streamUrl))
        assertFalse(AiBroadcastNetwork.isAiBroadcastUrl("https://example.com/live.m3u8"))
        assertEquals(channel.id, AiBroadcastNetwork.extractChannelId(streamUrl))
    }

    @Test
    fun `buildDjIntroSpeech includes DJ host, channel name, and airing program title`() {
        val config = AiBroadcastNetwork.builtInChannels.first()
        val slot = AiBroadcastNetwork.buildScheduleForChannel(config, nowMs = 1_700_000_000_000L, slotCount = 1).first()

        val speech = AiBroadcastNetwork.buildDjIntroSpeech(config, slot)

        assertTrue(speech.contains(config.name))
        assertTrue(speech.contains(config.djHostName))
        assertTrue(speech.contains(slot.program.title))
    }

    private class InMemorySharedPreferences(
        private val store: MutableMap<String, Any?>
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = store
        override fun getString(key: String?, defValue: String?): String? = store[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            store[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = store[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = store[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = store[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = store[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(store)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor(
            private val store: MutableMap<String, Any?>
        ) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { key?.let { pending[it] = values } }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { key?.let { pending[it] = value } }
            override fun remove(key: String?): SharedPreferences.Editor = apply { key?.let { removals.add(it) } }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clear) store.clear()
                removals.forEach { store.remove(it) }
                store.putAll(pending)
                pending.clear()
                removals.clear()
                clear = false
            }
        }
    }
}
