package com.btv.ui.parental

import com.btv.data.store.PinStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

const val PIN_LENGTH = 4

enum class PinStep { VERIFY, CREATE, CONFIRM }

/** What the PIN dialog shows. [serial] changes on every new prompt so the dialog clears its digits. */
data class PinPrompt(
    val step: PinStep,
    val title: String,
    val message: String,
    val error: String? = null,
    val serial: Int = 0
)

/**
 * The PIN conversation shared by Browse and Réglages:
 * - [require]: verify the PIN - or, if none exists yet, create one (creating it proves the user is the owner);
 * - [change]: verify the current PIN (if any), then create the new one.
 * A new PIN is always typed twice.
 */
class PinFlow(private val store: PinStore, private val scope: CoroutineScope) {
    private val _prompt = MutableStateFlow<PinPrompt?>(null)
    val prompt: StateFlow<PinPrompt?> = _prompt

    private var onSuccess: (() -> Unit)? = null
    private var changing = false
    private var pendingPin: String? = null
    private var serial = 0

    fun require(reason: String, onSuccess: () -> Unit) = start(reason, changing = false, onSuccess)

    fun change(onDone: () -> Unit = {}) = start("Modifier le code PIN", changing = true, onDone)

    private fun start(reason: String, changing: Boolean, onSuccess: () -> Unit) {
        this.onSuccess = onSuccess
        this.changing = changing
        pendingPin = null
        scope.launch {
            if (store.hasPin()) show(PinStep.VERIFY, if (changing) "Code PIN actuel" else "Code PIN", reason)
            else show(
                PinStep.CREATE, "Créer un code PIN",
                if (changing) "Il protégera les contenus adultes. Choisissez $PIN_LENGTH chiffres."
                else "Il protégera les contenus adultes. $reason"
            )
        }
    }

    fun cancel() {
        onSuccess = null
        pendingPin = null
        _prompt.value = null
    }

    fun submit(pin: String) {
        val current = _prompt.value ?: return
        if (pin.length != PIN_LENGTH || !pin.all(Char::isDigit)) return
        scope.launch {
            when (current.step) {
                PinStep.VERIFY -> when {
                    !store.verify(pin) -> show(current.step, current.title, current.message, "Code incorrect")
                    changing -> show(PinStep.CREATE, "Nouveau code PIN", "Choisissez $PIN_LENGTH chiffres.")
                    else -> finish()
                }
                PinStep.CREATE -> {
                    pendingPin = pin
                    show(PinStep.CONFIRM, "Confirmer le code PIN", "Saisissez-le une seconde fois.")
                }
                PinStep.CONFIRM -> if (pin == pendingPin) {
                    store.setPin(pin)
                    finish()
                } else {
                    pendingPin = null
                    show(PinStep.CREATE, "Créer un code PIN", "Choisissez $PIN_LENGTH chiffres.", "Les deux codes ne correspondent pas")
                }
            }
        }
    }

    private fun show(step: PinStep, title: String, message: String, error: String? = null) {
        _prompt.value = PinPrompt(step, title, message, error, ++serial)
    }

    private fun finish() {
        val action = onSuccess
        cancel()
        action?.invoke()
    }
}
