package com.comptapocket.sms.simulation

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Gestionnaire du Mode Simulation de Date pour les tests de ComptaPocket SMS.
 * Permet de modifier la date courante virtuelle de l'application afin de tester
 * le comportement des déclarations mensuelles (DM), des cotisations trimestrielles CNSS,
 * des acomptes provisionnels (AP), ainsi que les transitions de mois et d'échéances.
 */
object SimulationDateManager {

    private const val PREFS_NAME = "comptapocket_simulation_prefs"
    private const val KEY_SIMULATED_MILLIS = "simulated_millis"
    private const val KEY_IS_SIMULATION_ACTIVE = "is_simulation_active"

    private var prefs: SharedPreferences? = null

    private val _simulatedDateFlow = MutableStateFlow<Calendar?>(null)
    val simulatedDateFlow: StateFlow<Calendar?> = _simulatedDateFlow.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        // Purge et désactivation définitive de tout mode simulation pour passage en production
        prefs?.edit()?.clear()?.apply()
        _simulatedDateFlow.value = null
    }

    /**
     * Retourne toujours l'horloge réelle du système (production).
     */
    fun getTodayCalendar(): Calendar {
        return Calendar.getInstance()
    }

    fun isSimulationActive(): Boolean {
        return false
    }

    fun getTodayDateFormatted(): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return format.format(getTodayCalendar().time)
    }

    fun getTodayDisplayFormatted(): String {
        val format = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        return format.format(getTodayCalendar().time)
    }

    fun setSimulatedDate(year: Int, month: Int, dayOfMonth: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        _simulatedDateFlow.value = cal
        saveState(cal.timeInMillis, true)
    }

    fun setSimulatedDate(dateStr: String) {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        try {
            val parsed = format.parse(dateStr)
            if (parsed != null) {
                val cal = Calendar.getInstance().apply { time = parsed }
                _simulatedDateFlow.value = cal
                saveState(cal.timeInMillis, true)
            }
        } catch (_: Exception) {}
    }

    fun shiftDays(days: Int) {
        val cal = getTodayCalendar().apply {
            add(Calendar.DAY_OF_YEAR, days)
        }
        _simulatedDateFlow.value = cal
        saveState(cal.timeInMillis, true)
    }

    fun shiftMonths(months: Int) {
        val cal = getTodayCalendar().apply {
            add(Calendar.MONTH, months)
        }
        _simulatedDateFlow.value = cal
        saveState(cal.timeInMillis, true)
    }

    fun resetToRealDate() {
        _simulatedDateFlow.value = null
        saveState(0L, false)
    }

    private fun saveState(millis: Long, isActive: Boolean) {
        prefs?.edit()
            ?.putBoolean(KEY_IS_SIMULATION_ACTIVE, isActive)
            ?.putLong(KEY_SIMULATED_MILLIS, millis)
            ?.apply()
    }
}
