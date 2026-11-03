package com.hayeco.flipspaces.addon;

import android.os.Bundle;

/** 1.1.0: what the Pocket XR add-on tells the app. */
oneway interface IAddonCallback {
    /** The pointer accessibility service came on or went off. */
    void pointer(boolean on);

    /** Something went wrong with a touch or the cursor window, word for word. */
    void problem(String message);

    /** The listener came on or went off. */
    void notesState(boolean on);

    /**
     * The phone's notifications, newest first: "keys", "apps", "packages", "titles", "texts" (String[]), "posted"
     * (long[]) and "clearable" (boolean[]).
     */
    void notes(in Bundle rows);
}
