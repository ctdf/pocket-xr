package com.hayeco.flipspaces.addon;

import android.os.Bundle;
import com.hayeco.flipspaces.addon.IAddonCallback;

/**
 * 1.1.0: what the Pocket XR add-on does for the app. The add-on holds the two services Google's install check blocks on
 * sideloaded apps (the pointer accessibility service and the notification listener), so the app itself carries
 * neither. Only an app signed with the same key may bind it (the LINK signature permission).
 */
interface IAddon {
    /** The link's version, raised when a call is added. */
    int version();

    /** Where the add-on tells the app what changed; the latest one replaces the last. */
    void setCallback(IAddonCallback callback);

    /** The pointer accessibility service is switched on and bound. */
    boolean pointerOn();

    /** The notification listener is connected. */
    boolean notesOn();

    // The cursor window on the glasses, drawn by the accessibility service. displayId -1 removes it.
    oneway void overlayOn(int displayId);
    oneway void cursor(float x, float y, int zone, boolean pressed);
    oneway void preview(boolean shown, int left, int top, int right, int bottom);
    oneway void hand(boolean known, boolean present, float pinchProgress, boolean openPalm, String source);
    oneway void flash(String message, long millis);
    /**
     * Bundle: "labels" (String[]) and "icons" (a Bitmap list, null for none). An empty bundle hides the dock. Not
     * oneway: the icons are up to a few hundred KB, more than a oneway call's shared buffer should carry.
     */
    void dock(in Bundle items);
    oneway void spaceUp(boolean up);

    // Touches on the glasses display.
    oneway void tap(int displayId, float x, float y);
    oneway void dragStart(int displayId, float x, float y);
    oneway void dragTo(float x, float y);
    oneway void dragEnd(float x, float y);
    oneway void drag(int displayId, float fromX, float fromY, float toX, float toY);
    boolean back();

    /** The app windows on [displayId]: "ids", "bounds" (4 ints each), "layers" (int arrays), "titles" (String[]). */
    Bundle windows(int displayId);

    /** Presses DeX's full screen button on [displayId]; says what it did or saw. */
    String pressDexFullScreen(int displayId);

    // The phone's notifications, from the listener.
    void dismissNote(String key);
    void clearNotes();
    /** The packages with media playing now, or null when the listener is not connected. */
    String[] playingPackages();
}
