package com.puneet.friday.entry

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class FridayTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Friday"
            if (Build.VERSION.SDK_INT >= 29) subtitle = "Tap to speak"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(FridayEntryPoints.listenPendingIntent(this, TILE_REQUEST_CODE))
        } else {
            FridayEntryPoints.listenPendingIntent(this, TILE_REQUEST_CODE).send()
        }
    }

    private companion object {
        const val TILE_REQUEST_CODE = 42
    }
}
