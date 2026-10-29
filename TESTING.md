# Charlie Chaap Local-Network Test Checklist

## Automated/manual checks for this package

- Node.js server has no third-party npm runtime dependencies.
- Local HTTP server binds to `0.0.0.0` and prints LAN addresses.
- Seed menu contains 60 products and no external image URLs.
- Core HTML decorative and food images use local/embedded assets; no remote font or CDN is required.
- Staff order refresh interval is 750 ms.
- Server data persists in the local `DATA_DIR`; no cloud service is configured.

## Required acceptance test on the restaurant network

- [ ] Start server with `.env` configured and record its LAN address.
- [ ] Disconnect the router's internet/WAN but keep Wi-Fi enabled.
- [ ] Open customer menu on a second phone using `http://LAN-IP:3000/?mode=customer`.
- [ ] Place an order and confirm it appears on the staff dashboard in about one second.
- [ ] Change order status on staff device and confirm customer tracking updates.
- [ ] Test dine-in, takeaway and delivery, including validation.
- [ ] Test sold-out items and closed service.
- [ ] Edit menu, publish, and confirm customer phone sees the update.
- [ ] Mark payment received, export backup and restore a test backup.
- [ ] Restart the server and confirm order history persists.
- [ ] Confirm firewall permits the private LAN and router client isolation is disabled.

The acceptance tests above must be performed on the actual restaurant router and phones; they cannot be guaranteed solely from the package build.


## Separate entry points

- Open `/customer`: customer-only menu/checkout presentation.
- Open `/staff`: staff dashboard and login.
- Open `/admin`: authenticated management interface (currently shares staff permissions).
- Open `/`: should redirect to `/customer`.
- Confirm customer route hides staff navigation and staff route retains staff operations.
