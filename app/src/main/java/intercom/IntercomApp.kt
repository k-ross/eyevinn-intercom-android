package intercom

import android.app.Application
import intercom.call.CallManager
import intercom.data.SettingsStore

class IntercomApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var callManager: CallManager
        private set

    /** Auto-join happens once per process, not every time the activity is recreated. */
    var autoJoinHandled = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        callManager = CallManager(this, settings)
    }
}
