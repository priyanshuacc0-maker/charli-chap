# Charlie Chaap — phone-only setup

You need: one Android phone to act as the order desk, and the phones your customers and staff use.
No computer, no internet connection, no monthly fee.

## One-time setup (about 10 minutes)

1. **Install the APK** on the order-desk phone (the one that will stay at the counter). Open it.
   If Android asks about installing from unknown sources, allow it for your file manager or browser.
2. **Set the staff password.** Tap *Staff password*, choose at least 12 characters, and write it
   down. Staff need it to open the dashboard; customers never see it.
3. **Allow background running.** Tap *Keep running in background* and allow Charlie Chaap to run
   without battery restrictions. Without this, Android may stop the order desk after a while.
4. **Start it.** Tap *Start order desk*. The screen shows an address like
   `http://192.168.43.1:3000/customer`. Keep the phone plugged into a charger.
5. **Add your menu.** Tap *Owner controls*, sign in with the staff password, and replace the sample
   dishes with yours — name, half/full price, category, availability.

## Every day

1. Turn on the order-desk phone's **hotspot** (Settings → Hotspot), or join the restaurant Wi-Fi.
2. Open the app and tap **Start order desk**. A notification stays in the shade; that is normal —
   it means the desk is alive.
3. Customers join that hotspot, open the address shown in the app, and order from their own phones.
4. Staff open *Staff dashboard* in the app to see orders arrive and mark them preparing/done.

## Things that will save you trouble

* **Phones must be on the same network.** Mobile data alone cannot reach the order desk. If a
  customer's phone shows "Wi-Fi has no internet", choose to stay connected anyway, and turn off
  "switch to mobile data automatically" for that network.
* **Hotspots hold about 5–10 phones.** For more tables, buy a basic Wi-Fi router (₹1,000–1,500).
  The app works exactly the same on a router — the address just becomes something like
  `192.168.1.25`.
* **Keep it on charge.** The screen can be off; the order desk keeps running.
* **Back up weekly.** *Owner controls* has an export/backup option — save the file to Google Drive
  or send it to yourself. Orders live only on that phone.
* **After a reboot**, open the app and tap Start, or turn on *Start automatically after reboot*.

## Updating the app

When a new APK is available, install it over the old one — orders, menu and password stay. Do not
uninstall first, and never let anyone sign a build with a different key.
