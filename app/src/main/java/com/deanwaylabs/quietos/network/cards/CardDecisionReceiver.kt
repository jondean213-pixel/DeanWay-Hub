package com.deanwaylabs.quietos.network.cards

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notification action taps land here — APP-CARD-ROUTE-DESIGN §6.1.
 *
 * The receiver does no deciding of its own: it hands (card, verb) to
 * CardRelay.decide, which applies the pending/expiry guards and
 * signs with the Keystore key. A stale notification acting on an
 * already-settled card is refused there, not here.
 */
class CardDecisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cardId = intent.getStringExtra(CardNotification.EXTRA_CARD_ID)
            ?: return
        val verb = intent.getStringExtra(EXTRA_VERB) ?: return
        if (verb != "approve" && verb != "deny") return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CardRelay.decide(app, cardId, verb)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_VERB = "quietos_card_verb"
    }
}
