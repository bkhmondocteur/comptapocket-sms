package com.comptapocket.sms.sms

import java.util.Collections

/**
 * Verrou en mémoire synchronisé pour interdire formellement tout double envoi
 * d'un même rappel au modem GSM, même si plusieurs composants
 * (Alarme, Foreground Service, FCM Push) se réveillent à la même milliseconde.
 */
object SmsLock {
    private val inFlightIds = Collections.synchronizedSet(mutableSetOf<Int>())

    fun tryClaim(id: Int): Boolean {
        return inFlightIds.add(id)
    }

    fun release(id: Int) {
        inFlightIds.remove(id)
    }
}
