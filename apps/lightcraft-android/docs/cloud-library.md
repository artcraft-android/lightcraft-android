# Cloud library on the phone

A PC publishes a photo folder to cloud storage with `lc-cloud sync`
([lc-cloud](https://github.com/tinytom21/lightcraft-cloud); the remote layout is in its
[FORMAT.md](https://github.com/tinytom21/lightcraft-cloud/blob/main/FORMAT.md)). The phone then shows the photos
as thumbnails, downloads a small smart preview of each photo you open, lets you edit it, and sends
the edit back to the PC, which stays the master copy. The phone never holds the originals unless you
ask for a full-size export.

The phone reaches the storage through [rclone](https://rclone.org), built into the app, so any
rclone backend works (Google Drive, OneDrive, Dropbox, S3, WebDAV, SFTP, a folder…). What the app
needs is one **rclone config file** that contains one remote: a *phone-only* remote.

## Make a phone-only remote

Do this on the PC, where rclone is installed. Use a **separate remote** for the phone, not the one the
PC publishes with, so the phone can be cut off without touching the PC.

### Google Drive: limit it to one folder

1. In Drive, open the folder the PC publishes into (the one that holds `originals/`, `mobile/`,
   `inbox/`). Its address ends in `/folders/<folder id>`; copy that id.
2. Create a remote that starts at that folder:

   ```sh
   rclone config create phone drive scope=drive root_folder_id=<folder id>
   ```

   (or `rclone config`, then `n`, type `drive`, scope `1`, and paste the id as `root_folder_id`). The
   browser asks you to sign in and allow rclone.
3. Check that it sees the library and nothing else:

   ```sh
   rclone lsd phone:          # originals, mobile, inbox, ...
   ```

   The remote's root *is* the library, so in the app the remote is `phone:`.

### Other backends: a path

Make the remote as usual (`rclone config`) and give the app the path of the library on it:
`phone:LightCraft Cloud` for S3 or WebDAV, `phone:photos/cloud` for SFTP.

## Export just that remote to a file

`rclone config show` prints one remote (the section and its token), nothing else:

```sh
rclone config show phone > phone-rclone.conf
```

Copy `phone-rclone.conf` to the phone (a USB cable or a share you trust) and pick it in the app's
cloud settings together with the remote (`phone:`) and a name for this phone (letters, digits, `_`,
`-` and `.`, like `pixel-9`; the PC files its edits under that name). Delete the copy on the PC
afterwards if you like; the app keeps its own.

Do not use `~/.config/rclone/rclone.conf` itself: it holds every remote you have, including the
PC's.

**Encrypted library (rclone `crypt`):** the phone needs the crypt section as well as `phone`, and
`rclone config show` prints its passwords as `*** ENCRYPTED ***`, which the app can't decrypt with.
Copy that section from the config file instead, and pick the crypt remote in the app; see
[Encrypting the remote](https://github.com/tinytom21/lightcraft-cloud#encrypting-the-remote).

## What the file lets the phone do

The phone writes only to `inbox/<its name>/`, as new files that are never overwritten; the PC
validates every one before applying it. It reads thumbnails, previews, the manifest and, for a
full-size export, originals.

**Security.** The file holds an OAuth token. For Google Drive that token carries the scope you picked
(`drive`, the whole Drive), because Google offers no per-folder scope that rclone can use. What
confines the phone to the one folder is rclone: `root_folder_id` makes that folder the remote's root,
and the app only talks to that remote. Someone who gets the file and reads the token could use it
against all your Drive with other software. Treat `phone-rclone.conf` like a password: keep it off
shared storage, and if the phone is lost, revoke it at
<https://myaccount.google.com/permissions> (remove "rclone"), which also cuts off the PC's remote if
it uses the same rclone client; to avoid that, create the phone remote with its own client id
(`client_id` / `client_secret` from your own Google Cloud project) so the two tokens can be revoked
separately. A `drive.file`-scoped token would be narrower, but it only sees files the same app
created, which does not include the PC's uploads.

## In the app

Connect (the app checks it can reach the library), then Refresh: thumbnails appear, and the first
time you open a photo its preview downloads (about 2 to 3 MB). Edits are written to the photo's XMP
sidecar on the phone and sent to the PC when you push (or automatically, once the UI does so); with
no network they wait and go later. If the PC changed a photo's edits you have not sent yet, the PC's
version is not applied until yours is sent; the PC then keeps whichever edit is newer. After the PC
has applied an edit it publishes it, and the next refresh shows it.

Smart previews are cached up to a size you can set (2 GiB by default); the least recently opened
are deleted first and downloaded again when needed.

Raw + JPEG pairs of one shot are one photo: the app lists the partner too, but hides it
(`cloud_hidden_photos`).

## Building the app

The app contains rclone's C library, which is built separately; see "librclone" in the app's
README (`scripts/build-librclone.sh`).
