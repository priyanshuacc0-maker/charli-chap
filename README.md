# Charlie Chaap — Local-Network Digital Menu & Order Desk

This edition is designed for a restaurant that needs **no internet connection and no cloud backend**. One computer or Android device runs a small Node.js server on the restaurant's local Wi-Fi/router. Customer phones and staff devices connect to that server over the LAN. Orders, menu, settings and order statuses stay on the local server device.

## What works locally

- Customer menu, search, categories, sizes, quantities, cart and checkout.
- Dine-in, takeaway and delivery order forms.
- Shared order queue and status updates across devices on the same LAN.
- Menu editing, availability, service open/closed toggle, tax/delivery settings and CSV export.
- Staff password sign-in, server-side price validation, backup/restore and persistent JSON storage.
- No third-party npm packages, cloud database, CDN, remote font, or external image requests are required by the app. Food images use assets embedded in the HTML; menu images can be changed to locally embedded assets.
- Staff order refresh runs every 750 ms while signed in, so updates typically appear within about one second on a healthy local network.

## Requirements

- One always-on computer/laptop (recommended) with Node.js 18 or newer. A compatible Android terminal environment can also run Node.js, but a computer is generally easier to keep running reliably.
- A Wi-Fi router/access point. **The router does not need an internet connection.** Phones must connect to the same local Wi-Fi network; guest Wi-Fi/client isolation must be disabled so devices can reach the server.

## Setup

1. Connect the server computer and customer/staff phones to the same restaurant Wi-Fi/router. Internet service is not needed.
2. Install Node.js 18+ on the server computer.
3. In this folder, copy `.env.example` to `.env`. Change `ADMIN_PASSWORD` to a unique password of at least 12 characters.
4. Open a terminal in this folder and run `npm start`.
5. The terminal prints the local addresses, for example `http://192.168.1.25:3000`. Use the actual address printed on your device.
6. Open the customer website at `http://LAN-IP:3000/customer`, the staff dashboard at `http://LAN-IP:3000/staff`, and the owner controls at `http://LAN-IP:3000/admin` (replace `LAN-IP` with the server address printed in the terminal). The root URL redirects to the customer website. Staff and owner routes use the protected staff sign-in; owner controls are currently the same authenticated management interface, not a separate role with different permissions.
7. If a computer firewall asks, allow Node.js on **private/local networks only**. Do not expose port 3000 to the public internet or configure router port forwarding.
8. Optionally print the customer URL as a QR code using any offline QR-code tool, or create one on a device that supports offline generation. The QR code should contain the local customer URL, for example `http://LAN-IP:3000/customer`. Print one QR per table and configure its table number in the ordering flow if table-specific identification is required.

## How the local network works

- The server device is the source of truth for menu, orders and settings.
- Customer devices send orders to the server over local Wi-Fi; staff devices read and update the same orders.
- Orders persist in `data/state.json` on the server device. Do not delete this file. Use **More → Export full backup** regularly.
- Keep the server device and router powered on while taking orders. If either stops, new orders and synchronization pause until it returns.
- If Wi-Fi disconnects, existing server data remains safe, but phones cannot submit or sync orders until they reconnect.
- This is a single-server design. Do not run multiple server instances against the same data folder.

## Security and practical limits

- This is a trusted local-network service, not an internet-facing deployment. Use a strong staff password and a private Wi-Fi password.
- Do not connect customer devices to the same network if you do not trust them; the app is designed for a controlled restaurant LAN.
- Plain local HTTP is suitable only for a trusted private network. Do not port-forward it to the internet.
- No online payment gateway is integrated. Cash/UPI-at-counter and cash-on-delivery can be recorded; staff marks payment received.
- Remote photos, cloud services and online fonts are not used by the core ordering flow. If you add external image URLs manually, those specific images will not load without internet; use local assets for strict offline operation.

## Configuration

| Setting | Purpose | Default |
|---|---|---|
| `ADMIN_PASSWORD` | Staff sign-in password | Must be set to 12+ characters |
| `PORT` | Local server port | `3000` |
| `HOST` | Network interface | `0.0.0.0` |
| `DATA_DIR` | Persistent local data folder | `./data` |

## Test before service

1. Start the server and note its LAN address.
2. Turn off the router's internet/WAN connection while keeping Wi-Fi active.
3. Open the customer menu from a second phone and submit a small test order.
4. Confirm it appears on the staff dashboard within about one second.
5. Change the status to Preparing and then Completed; check the order status from the customer device.
6. Test sold-out items, closed service, table number, takeaway, delivery, payment marking, backup and restore.
7. Restart the server and verify orders still exist.

## Commands

```sh
# start service
npm start

# check service health from any device on the LAN
http://LAN-IP:3000/api/health
```

The package is local-network ready but is not running until you start it on your own device. The local server must remain powered on for multi-device ordering.
