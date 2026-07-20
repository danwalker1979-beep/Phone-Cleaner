# Clean AF — phone cleanup, honestly

An on-device tool for tidying up a phone. Open `index.html` in a phone
browser (no install) and everything runs locally — no photos, apps, or data
ever leave the device.

## What it does

| Tool | What happens |
| --- | --- |
| **Duplicate photos** | Pick photos (or a folder); it groups identical **and** near-identical shots side by side, keeps the best copy, and builds an exact delete list with file names and folders. Matching uses SHA-256 for exact copies and an 8×8 average-hash for look-alikes — all in the browser. |
| **Photo dashboard** | Every service that can auto-upload photos (Google Photos, iCloud, OneDrive, Amazon Photos, Dropbox, Samsung Cloud…) with the exact steps to switch each one off, plus a "which one is my backup home" tracker. |
| **App inventory** | Track apps with a plain-English **description**, whether they need a **paid subscription** (free trials counted as paid), and a **free alternative** that does the same job. Sort by size, then export a **confirm-first uninstall list** — you approve every removal. Includes a searchable reference list of commonly-forgotten apps. |
| **Cleanup** | Step-by-step guidance for finding and safely removing folders left behind by uninstalled apps, on Android and iPhone. |
| **Voice assistant** | Hands-free **press-and-hold** navigation using the phone's own speech engine — hold the mic, say "find duplicates" / "where do my photos go" / "show my apps" / "read this", release to send. It only listens while held. Offline and private. |

## About the app list

The app inventory does exactly what a cleanup list should: names each app, says
what it does, flags whether it charges a subscription, and suggests a free
alternative — then exports a list you confirm before uninstalling anything.

Two honest notes:

- **A web page can't read your installed-app list automatically.** So you add
  the apps you're unsure about, or pull them from the built-in reference list
  (which already has the description, subscription flag, and free alternative).
  Reading the full installed list for you needs a native app with OS
  permission.
- **Nothing is deleted or uninstalled without you.** By design the phone makes
  you confirm every uninstall — this tool builds the exact list and walks you
  to the right screen. It never removes anything on its own.

The same applies to photo backups: no app can flip another app's cloud toggle,
so the photo dashboard shows you exactly where each switch lives instead.

A native (installed) Android/iOS app is the next step for the two web-only
gaps: reading your installed apps automatically, and a live conversational AI
assistant.

## Run it

It's a single self-contained file. Open `index.html` locally, or host the repo
with GitHub Pages and open the URL on your phone.
