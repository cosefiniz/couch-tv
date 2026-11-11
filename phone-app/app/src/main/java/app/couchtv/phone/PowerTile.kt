package app.couchtv.phone

import android.annotation.TargetApi
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A quick settings tile ("TV gücü") that turns the TV on or off through the bridge. Android 7+. */
@TargetApi(24)
class PowerTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
        Thread {
            Hub.api(this, "/api/power", """{"state":"toggle"}""")
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
        }.start()
    }
}
