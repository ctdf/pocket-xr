# Tell new users about the add-on, with a one-tap install

New users now hear about the optional Pocket XR add-on during setup, and can install it with one tap.

## What changes

- **Tutorial:** the first-run tutorial has a new page, "Phone notifications in your glasses", just before its last
  page, so it still ends on where Exit is. The page says what the add-on gives (the phone's notifications in the
  glasses, and the pointer outside the Space) and has the add-on's button under it. Next or the close mark skips it.
  The Menu's "How to use the trackpad" pages are unchanged.
- **Glasses setup check:** a passed check ("All set") shows the same add-on step above Done until the add-on is
  installed with notification access on. Done still closes the card.
- **The button** (`AddonPanel`, state from `core/AddonSetup.step`):
  - Not installed: **Install add-on**. It reads GitHub's release list, picks the newest `pocketxr-addon-X.Y.apk` (no
    drafts or pre-releases, and only from `github.com/pocket-xr/pocket-xr/releases/download/`), downloads it into the
    cache with a progress bar, and opens Android's install prompt through the existing FileProvider.
  - "Install unknown apps" off: **Allow installs** opens that setting for Pocket XR. When the user comes back with it
    on, the install goes on by itself.
  - Download failed: a plain reason (no connection, GitHub busy, no release found), **Try again** and **Open releases
    page**.
  - Installed: shown as done, with **Turn on notifications** (access off) or **Open add-on setup** (access on), which
    open the add-on's SetupActivity (or Android's notification access page for an add-on too old to have it).
  - Google Play copies: **Get the add-on** opens its Play page. Nothing is downloaded.
- **Permission:** `REQUEST_INSTALL_PACKAGES` is declared in debug builds and in every release except the Google Play
  one (`app/src/selfinstall/AndroidManifest.xml`, added to the release source set only when `pocketxr.play` is not
  set).
- README setup step 3 and "The add-on" now describe the button. PRIVACY.md lists the add-on download as a request to
  GitHub.

## Note on "reuse the updater's path"

The in-app updater does not download or install anything itself: `UpdateChecker.open` hands the APK link to the browser,
and the app had no `REQUEST_INSTALL_PACKAGES`. So `platform/AddonInstaller.kt` is a new, small download and install-prompt
path. The Pocket XR update could use it later, but this change leaves the updater as it is.

## Tests

- `core`: `AddonSetup0121Test` (18 tests): not installed, installed with access off and on, installed winning over a
  stale download, download failure and its reasons, progress, missing install permission, after the prompt, Google
  Play, picking the newest add-on release, skipping pre-releases, drafts and other hosts, plain words (no em or en
  dashes, no "we", "our" or "us"), and the tutorial's page order. `Onboarding0119Test` updated for the new page.
- `app`: `AddonPanel0121Test` (Robolectric, 4 tests): Install shown, Allow installs opening
  `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for the package, an installed add-on shown as done, and the passed check showing
  the step with Done still closing it.
- `:core:test :app:testDebugUnitTest :app:assembleDebug` pass (1940 tests, 0 failures). The release manifest was
  checked to include the permission. The Play build could not be built here (no keystore.properties), so whether its
  manifest leaves the permission out is not checked.

Not tried on a phone yet. Worth checking by hand: the install prompt on the Flip 7, coming back from "Install unknown
apps", and the tutorial page's height on a short landscape screen.

No version bump, release or merge.
