package com.theveloper.pixelplay.data.equalizer

import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.media.audiofx.LoudnessEnhancer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Android's built-in audio effects (Equalizer, BassBoost, Virtualizer, LoudnessEnhancer).
 * Supports multiple simultaneous audio sessions for seamless crossfade.
 * 
 * Thread-safe: All effect operations run on the main thread or use thread-safe collections.
 */
@Singleton
class EqualizerManager @Inject constructor() {
    
    companion object {
        private const val TAG = "EqualizerManager"
        private const val NUM_BANDS = 10
        private const val MIN_LEVEL = -15
        private const val MAX_LEVEL = 15
    }
    
    private data class SessionEffects(
        val sessionId: Int,
        val equalizer: Equalizer? = null,
        val bassBoost: BassBoost? = null,
        val virtualizer: Virtualizer? = null,
        val loudnessEnhancer: LoudnessEnhancer? = null,
        var minEqLevel: Short = -1500,
        var maxEqLevel: Short = 1500
    ) {
        fun release() {
            try { equalizer?.release() } catch (e: Exception) { Timber.tag(TAG).e(e, "Error releasing Equalizer") }
            try { bassBoost?.release() } catch (e: Exception) { Timber.tag(TAG).e(e, "Error releasing BassBoost") }
            try { virtualizer?.release() } catch (e: Exception) { Timber.tag(TAG).e(e, "Error releasing Virtualizer") }
            try { loudnessEnhancer?.release() } catch (e: Exception) { Timber.tag(TAG).e(e, "Error releasing LoudnessEnhancer") }
        }
    }

    private val activeSessions = ConcurrentHashMap<Int, SessionEffects>()
    
    // Normalized band levels (-15 to +15 for UI)
    private val _bandLevels = MutableStateFlow(List(NUM_BANDS) { 0 })
    val bandLevels: StateFlow<List<Int>> = _bandLevels.asStateFlow()
    
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    
    private val _currentPresetName = MutableStateFlow("flat")
    val currentPresetName: StateFlow<String> = _currentPresetName.asStateFlow()
    
    private val _bassBoostEnabled = MutableStateFlow(false)
    val bassBoostEnabled: StateFlow<Boolean> = _bassBoostEnabled.asStateFlow()

    private val _bassBoostStrength = MutableStateFlow(0)
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength.asStateFlow()

    private val _virtualizerEnabled = MutableStateFlow(false)
    val virtualizerEnabled: StateFlow<Boolean> = _virtualizerEnabled.asStateFlow()

    private val _virtualizerStrength = MutableStateFlow(0)
    val virtualizerStrength: StateFlow<Int> = _virtualizerStrength.asStateFlow()

    private val _loudnessEnhancerEnabled = MutableStateFlow(false)
    val loudnessEnhancerEnabled: StateFlow<Boolean> = _loudnessEnhancerEnabled.asStateFlow()

    private val _loudnessEnhancerStrength = MutableStateFlow(0)
    val loudnessEnhancerStrength: StateFlow<Int> = _loudnessEnhancerStrength.asStateFlow()
    
    // Global device capabilities (Checking existence of effect UUIDs)
    private var isBassBoostSupportedGlobal = false
    private var isVirtualizerSupportedGlobal = false
    
    init {
        checkDeviceSupport()
    }
    
    private fun checkDeviceSupport() {
        try {
            val effects = android.media.audiofx.AudioEffect.queryEffects()
            isBassBoostSupportedGlobal = effects.any { it.type == android.media.audiofx.AudioEffect.EFFECT_TYPE_BASS_BOOST }
            isVirtualizerSupportedGlobal = effects.any { it.type == android.media.audiofx.AudioEffect.EFFECT_TYPE_VIRTUALIZER }
            Timber.tag(TAG).d("Global Support Check - BassBoost: $isBassBoostSupportedGlobal, Virtualizer: $isVirtualizerSupportedGlobal")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to query global audio effects")
        }
    }

    /**
     * Attaches audio effects to an audio session ID.
     */
    suspend fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) {
            Timber.tag(TAG).w("Invalid audio session ID: $audioSessionId")
            return
        }
        
        if (activeSessions.containsKey(audioSessionId)) {
            Timber.tag(TAG).d("Already attached to session $audioSessionId")
            return
        }
        
        Timber.tag(TAG).d("Attaching to audio session: $audioSessionId")
        
        try {
            val eq = try {
                Equalizer(0, audioSessionId).apply {
                    enabled = _isEnabled.value
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to initialize Equalizer for session $audioSessionId")
                null
            }

            val bb = if (isBassBoostSupportedGlobal) {
                try {
                    BassBoost(0, audioSessionId).apply {
                        enabled = _bassBoostEnabled.value
                        if (strengthSupported) {
                            setStrength(_bassBoostStrength.value.toShort())
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to initialize BassBoost for session $audioSessionId")
                    null
                }
            } else null

            val virt = if (isVirtualizerSupportedGlobal) {
                try {
                    Virtualizer(0, audioSessionId).apply {
                        enabled = _virtualizerEnabled.value
                        if (strengthSupported) {
                            setStrength(_virtualizerStrength.value.toShort())
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to initialize Virtualizer for session $audioSessionId")
                    null
                }
            } else null

            val le = try {
                LoudnessEnhancer(audioSessionId).apply {
                    enabled = _loudnessEnhancerEnabled.value
                    setTargetGain(_loudnessEnhancerStrength.value)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "LoudnessEnhancer not supported for session $audioSessionId")
                null
            }

            val sessionEffects = SessionEffects(
                sessionId = audioSessionId,
                equalizer = eq,
                bassBoost = bb,
                virtualizer = virt,
                loudnessEnhancer = le
            )
            
            eq?.let {
                sessionEffects.minEqLevel = it.bandLevelRange[0]
                sessionEffects.maxEqLevel = it.bandLevelRange[1]
                applyBandLevelsToSession(sessionEffects, _bandLevels.value)
            }
            
            activeSessions[audioSessionId] = sessionEffects
            Timber.tag(TAG).d("Effects attached successfully to session $audioSessionId")
            
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Fatal error attaching audio effects to session $audioSessionId")
        }
    }

    /**
     * Detaches audio effects from an audio session ID.
     */
    fun detachFromAudioSession(audioSessionId: Int) {
        Timber.tag(TAG).d("Detaching from audio session: $audioSessionId")
        activeSessions.remove(audioSessionId)?.release()
    }
    
    /**
     * Enables or disables the equalizer on all active sessions.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        activeSessions.values.forEach { session ->
            try {
                session.equalizer?.enabled = enabled
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to set EQ enabled state for session ${session.sessionId}")
            }
        }
        Timber.tag(TAG).d("Equalizer enabled: $enabled")
    }
    
    /**
     * Sets the level for a specific band on all active sessions.
     */
    fun setBandLevel(bandIndex: Int, level: Int) {
        if (bandIndex !in 0 until NUM_BANDS) return
        
        val clampedLevel = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
        val newLevels = _bandLevels.value.toMutableList()
        newLevels[bandIndex] = clampedLevel
        _bandLevels.value = newLevels
        
        activeSessions.values.forEach { session ->
            applyBandLevelsToSession(session, newLevels)
        }
        
        _currentPresetName.value = "custom"
    }
    
    /**
     * Applies a preset to the equalizer on all active sessions.
     */
    fun applyPreset(preset: EqualizerPreset) {
        _currentPresetName.value = preset.name
        _bandLevels.value = preset.bandLevels
        activeSessions.values.forEach { session ->
            applyBandLevelsToSession(session, preset.bandLevels)
        }
        Timber.tag(TAG).d("Applied preset: ${preset.displayName}")
    }

    /**
     * Sets bass boost enabled state on all active sessions.
     */
    fun setBassBoostEnabled(enabled: Boolean) {
        _bassBoostEnabled.value = enabled
        activeSessions.values.forEach { session ->
            try {
                session.bassBoost?.enabled = enabled
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to set bass boost enabled for session ${session.sessionId}")
            }
        }
    }
    
    /**
     * Sets bass boost strength (0-1000) on all active sessions.
     */
    fun setBassBoostStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, 1000)
        _bassBoostStrength.value = clampedStrength
        
        activeSessions.values.forEach { session ->
            try {
                session.bassBoost?.apply {
                    if (strengthSupported) {
                        setStrength(clampedStrength.toShort())
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to set bass boost strength for session ${session.sessionId}")
            }
        }
    }

    /**
     * Sets virtualizer enabled state on all active sessions.
     */
    fun setVirtualizerEnabled(enabled: Boolean) {
        _virtualizerEnabled.value = enabled
        activeSessions.values.forEach { session ->
            try {
                session.virtualizer?.enabled = enabled
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to set virtualizer enabled for session ${session.sessionId}")
            }
        }
    }
    
    /**
     * Sets virtualizer (surround) strength (0-1000) on all active sessions.
     */
    fun setVirtualizerStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, 1000)
        _virtualizerStrength.value = clampedStrength
        
        activeSessions.values.forEach { session ->
            try {
                session.virtualizer?.apply {
                    if (strengthSupported) {
                        setStrength(clampedStrength.toShort())
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to set virtualizer strength for session ${session.sessionId}")
            }
        }
    }

    /**
     * Sets loudness enhancer enabled state on all active sessions.
     */
    fun setLoudnessEnhancerEnabled(enabled: Boolean) {
        _loudnessEnhancerEnabled.value = enabled
        activeSessions.values.forEach { session ->
            try {
                session.loudnessEnhancer?.enabled = enabled
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to set loudness enhancer enabled for session ${session.sessionId}")
            }
        }
    }

    /**
     * Sets loudness enhancer strength (gain in mB) on all active sessions.
     */
    fun setLoudnessEnhancerStrength(strength: Int) {
        val clampedStrength = strength.coerceIn(0, 3000)
        _loudnessEnhancerStrength.value = clampedStrength

        activeSessions.values.forEach { session ->
            try {
                session.loudnessEnhancer?.setTargetGain(clampedStrength)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to set loudness enhancer strength for session ${session.sessionId}")
            }
        }
    }
    
    /**
     * Restores equalizer state from saved preferences.
     */
    fun restoreState(
        enabled: Boolean,
        presetName: String,
        customBands: List<Int>,
        bassBoostEnabled: Boolean,
        bassBoostStrength: Int,
        virtualizerEnabled: Boolean,
        virtualizerStrength: Int,
        loudnessEnabled: Boolean,
        loudnessStrength: Int
    ) {
        _isEnabled.value = enabled
        _bassBoostEnabled.value = bassBoostEnabled
        _bassBoostStrength.value = bassBoostStrength
        _virtualizerEnabled.value = virtualizerEnabled
        _virtualizerStrength.value = virtualizerStrength
        _loudnessEnhancerEnabled.value = loudnessEnabled
        _loudnessEnhancerStrength.value = loudnessStrength
        
        val preset = if (presetName == "custom") {
            EqualizerPreset.custom(customBands)
        } else {
            EqualizerPreset.fromName(presetName)
        }
        
        _currentPresetName.value = preset.name
        _bandLevels.value = preset.bandLevels
        
        // Apply to all currently active sessions
        activeSessions.values.forEach { session ->
            try {
                session.equalizer?.enabled = enabled
                applyBandLevelsToSession(session, preset.bandLevels)

                session.bassBoost?.apply {
                    this.enabled = bassBoostEnabled
                    if (strengthSupported) setStrength(bassBoostStrength.toShort())
                }

                session.virtualizer?.apply {
                    this.enabled = virtualizerEnabled
                    if (strengthSupported) setStrength(virtualizerStrength.toShort())
                }

                session.loudnessEnhancer?.apply {
                    this.enabled = loudnessEnabled
                    setTargetGain(loudnessStrength)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error restoring state for session ${session.sessionId}")
            }
        }
    }
    
    private fun applyBandLevelsToSession(session: SessionEffects, levels: List<Int>) {
        val eq = session.equalizer ?: return
        val deviceBandCount = eq.numberOfBands.toInt()
        
        if (deviceBandCount <= 0) return
        
        val uiBandCount = levels.size
        
        if (deviceBandCount >= uiBandCount) {
            levels.forEachIndexed { index, level ->
                applyBandLevelToSessionDirect(session, index, level)
            }
        } else {
            val ratio = uiBandCount.toFloat() / deviceBandCount.toFloat()
            for (deviceBand in 0 until deviceBandCount) {
                val startUiBand = (deviceBand * ratio).toInt()
                val endUiBand = ((deviceBand + 1) * ratio).toInt().coerceAtMost(uiBandCount)
                var sum = 0
                var count = 0
                for (uiBand in startUiBand until endUiBand) {
                    if (uiBand < levels.size) {
                        sum += levels[uiBand]
                        count++
                    }
                }
                val averageLevel = if (count > 0) sum / count else 0
                applyBandLevelToSessionDirect(session, deviceBand, averageLevel)
            }
        }
    }
    
    private fun applyBandLevelToSessionDirect(session: SessionEffects, bandIndex: Int, normalizedLevel: Int) {
        val eq = session.equalizer ?: return
        if (bandIndex >= eq.numberOfBands) return
        
        val range = session.maxEqLevel - session.minEqLevel
        val millibelLevel = (session.minEqLevel + (normalizedLevel + 15) * range / 30).toShort()
        
        try {
            eq.setBandLevel(bandIndex.toShort(), millibelLevel)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set band $bandIndex level for session ${session.sessionId}")
        }
    }
    
    /**
     * Gets the center frequencies for the first active session or defaults.
     */
    fun getBandFrequencies(): List<Int> {
        val eq = activeSessions.values.firstOrNull()?.equalizer ?: return listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        return (0 until eq.numberOfBands).map { band ->
            eq.getCenterFreq(band.toShort()) / 1000
        }
    }
    
    /**
     * Checks if bass boost is supported on this device.
     */
    fun isBassBoostSupported(): Boolean = isBassBoostSupportedGlobal
    
    /**
     * Checks if virtualizer is supported on this device.
     */
    fun isVirtualizerSupported(): Boolean = isVirtualizerSupportedGlobal

    /**
     * Checks if loudness enhancer is supported on this device.
     */
    fun isLoudnessEnhancerSupported(): Boolean = true // Usually supported via API
    
    /**
     * Releases all audio effect resources for all sessions.
     */
    fun release() {
        Timber.tag(TAG).d("Releasing all audio effects for all sessions")
        activeSessions.values.forEach { it.release() }
        activeSessions.clear()
    }
}
