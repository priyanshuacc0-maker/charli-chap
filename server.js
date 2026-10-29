'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const os = require('node:os');
const { URL } = require('node:url');

// Load a simple local .env file without any third-party package. Hosting
// providers should set the same values through their environment settings.
function loadDotEnv(file) {
  try {
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;
      const match = trimmed.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
      if (!match || process.env[match[1]] !== undefined) continue;
      let value = match[2].trim();
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
      else value = value.replace(/\s+#.*$/, '').trim();
      process.env[match[1]] = value;
    }
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
}
loadDotEnv(path.join(__dirname, '.env'));

const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || '0.0.0.0';
const DATA_DIR = path.resolve(process.env.DATA_DIR || path.join(__dirname, 'data'));
const STATE_FILE = path.join(DATA_DIR, 'state.json');
const INITIAL_MENU_FILE = path.join(__dirname, 'data', 'menu.json');
const ADMIN_PASSWORD = String(process.env.ADMIN_PASSWORD || '');
const SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const MAX_BODY_BYTES = 35 * 1024 * 1024;

if (ADMIN_PASSWORD.length < 12) {
  console.error('Set ADMIN_PASSWORD to a strong password of at least 12 characters before starting the service.');
  process.exit(1);
}

fs.mkdirSync(DATA_DIR, { recursive: true });

function normalize(value) {
  return String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim();
}
function isObject(value) { return !!value && typeof value === 'object' && !Array.isArray(value); }
function cleanText(value, max = 400) { return String(value || '').trim().slice(0, max); }
function roundMoney(value) { return Math.round((Number(value) + Number.EPSILON) * 100) / 100; }
function validPrice(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0 || n > 1000000) throw new Error('Menu prices must be between ₹0 and ₹10,00,000.');
  return n;
}
function cleanMenuItem(raw) {
  if (!isObject(raw)) throw new Error('A menu item is invalid.');
  const n = cleanText(raw.n || raw.name, 80);
  const c = cleanText(raw.c || raw.category || 'Other', 50) || 'Other';
  if (!n) throw new Error('Every menu item needs a name.');
  const h = validPrice(raw.h);
  const f = validPrice(raw.f);
  if (h === null && f === null) throw new Error(`Add at least one price for ${n}.`);
  const rawImage = typeof raw.image === 'string' ? raw.image : '';
  if (rawImage.length > 600000) throw new Error(`The image for ${n} is too large. Re-crop it before publishing.`);
  if (rawImage && !/^https?:\/\//i.test(rawImage) && !/^data:image\/(png|jpe?g|webp|gif);base64,/i.test(rawImage)) {
    throw new Error(`The image for ${n} must be an HTTPS URL or an image upload.`);
  }
  const aliases = Array.isArray(raw.a) ? raw.a.slice(0, 40).map(v => cleanText(v, 100)).filter(Boolean) : [];
  if (!aliases.some(v => normalize(v) === normalize(n))) aliases.unshift(n);
  return {
    n, c, h, f,
    hLabel: cleanText(raw.hLabel, 24), fLabel: cleanText(raw.fLabel, 24),
    a: [...new Set(aliases)], custom: !!raw.custom,
    available: raw.available !== false, image: rawImage
  };
}
function cleanMenu(items) {
  if (!Array.isArray(items) || items.length < 1 || items.length > 600) throw new Error('The menu must contain between 1 and 600 items.');
  const result = items.map(cleanMenuItem);
  const seen = new Set();
  for (const item of result) {
    const key = normalize(item.n);
    if (seen.has(key)) throw new Error(`Duplicate menu item: ${item.n}.`);
    seen.add(key);
  }
  return result;
}
function cleanConfig(raw, previous = { isOpen: true, taxPercent: 0, deliveryFee: 0 }) {
  const config = { ...previous };
  if (raw.isOpen !== undefined) config.isOpen = !!raw.isOpen;
  if (raw.taxPercent !== undefined) {
    const tax = Number(raw.taxPercent);
    if (!Number.isFinite(tax) || tax < 0 || tax > 100) throw new Error('Tax must be between 0 and 100 percent.');
    config.taxPercent = tax;
  }
  if (raw.deliveryFee !== undefined) {
    const fee = Number(raw.deliveryFee);
    if (!Number.isFinite(fee) || fee < 0 || fee > 100000) throw new Error('Delivery fee must be between ₹0 and ₹1,00,000.');
    config.deliveryFee = fee;
  }
  return config;
}

function initialState() {
  let menu = [];
  try { menu = cleanMenu(JSON.parse(fs.readFileSync(INITIAL_MENU_FILE, 'utf8'))); }
  catch (error) {
    console.error('Could not load data/menu.json:', error.message);
    menu = [{ n: 'Menu setup required', c: 'Other', h: 1, f: null, a: ['Menu setup required'], custom: true, available: false, image: '' }];
  }
  return { version: 1, orders: [], menu, config: { isOpen: true, taxPercent: 0, deliveryFee: 0 }, nextOrderNo: 1, updatedAt: new Date().toISOString() };
}
function loadState() {
  try {
    const saved = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    if (!isObject(saved) || !Array.isArray(saved.orders) || !Array.isArray(saved.menu)) throw new Error('bad state');
    saved.menu = cleanMenu(saved.menu);
    saved.config = cleanConfig(saved.config || {});
    saved.orders = saved.orders.filter(isObject);
    const largestId = saved.orders.reduce((max, order) => Math.max(max, Number(order.id) || 0), 0);
    saved.nextOrderNo = Math.max(Number(saved.nextOrderNo) || 1, largestId + 1);
    return saved;
  } catch (_) {
    const state = initialState();
    saveState(state);
    return state;
  }
}
let state = loadState();
function saveState(next = state) {
  next.updatedAt = new Date().toISOString();
  const tmp = `${STATE_FILE}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(next, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, STATE_FILE);
}

const sessions = new Map();
const rateLimits = new Map();
function getClientKey(req) { return (req.socket.remoteAddress || 'unknown').replace(/^::ffff:/, ''); }
function rateLimit(req, bucket, max, windowMs) {
  const now = Date.now();
  const key = `${bucket}:${getClientKey(req)}`;
  const old = rateLimits.get(key);
  if (!old || now - old.start >= windowMs) { rateLimits.set(key, { start: now, count: 1 }); return true; }
  old.count += 1;
  return old.count <= max;
}
function equalSecret(a, b) {
  const aa = Buffer.from(String(a)); const bb = Buffer.from(String(b));
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}
function parseCookies(req) {
  const list = {};
  for (const part of String(req.headers.cookie || '').split(';')) {
    const i = part.indexOf('='); if (i < 0) continue;
    const key = part.slice(0, i).trim();
    try { list[key] = decodeURIComponent(part.slice(i + 1).trim()); } catch (_) { list[key] = ''; }
  }
  return list;
}
function currentSession(req) {
  const token = parseCookies(req).cc_staff_session;
  const session = token && sessions.get(token);
  if (!session) return null;
  if (session.expiresAt < Date.now()) { sessions.delete(token); return null; }
  session.expiresAt = Date.now() + SESSION_TTL_MS;
  return { token, session };
}
function requireStaff(req, res) {
  const found = currentSession(req);
  if (found) return found;
  sendJson(res, 401, { error: 'Staff sign-in is required for this action.' });
  return null;
}
function setSessionCookie(req, res, token, clear = false) {
  const secure = req.socket.encrypted || req.headers['x-forwarded-proto'] === 'https';
  const values = [`cc_staff_session=${clear ? '' : encodeURIComponent(token)}`, 'Path=/', 'HttpOnly', 'SameSite=Strict'];
  values.push(`Max-Age=${clear ? 0 : Math.floor(SESSION_TTL_MS / 1000)}`);
  if (secure) values.push('Secure');
  res.setHeader('Set-Cookie', values.join('; '));
}
function sendJson(res, status, data, headers = {}) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'same-origin',
    'Content-Length': Buffer.byteLength(body),
    ...headers
  });
  res.end(body);
}
function sendText(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(value);
}
function readBody(req, limit = MAX_BODY_BYTES) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', chunk => {
      size += chunk.length;
      if (size > limit) { reject(Object.assign(new Error('Request body is too large.'), { status: 413 })); req.destroy(); return; }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); }
      catch (_) { reject(Object.assign(new Error('Request body must be valid JSON.'), { status: 400 })); }
    });
    req.on('error', reject);
  });
}
function findMenuItem(name) { const key = normalize(name); return state.menu.find(item => normalize(item.n) === key); }
function prepareItems(rawItems, allowUnavailable = false) {
  if (!Array.isArray(rawItems) || rawItems.length < 1 || rawItems.length > 100) throw new Error('An order must contain between 1 and 100 lines.');
  return rawItems.map(raw => {
    const item = findMenuItem(raw && raw.name);
    if (!item) throw new Error(`Menu item “${cleanText(raw && raw.name, 80)}” no longer exists.`);
    if (!item.available && !allowUnavailable) throw new Error(`${item.n} is currently unavailable.`);
    const size = raw.size === 'f' ? 'f' : raw.size === 'h' ? 'h' : '';
    if (!size) throw new Error(`Choose a valid size for ${item.n}.`);
    const rate = item[size];
    if (rate === null || rate === undefined || !Number.isFinite(Number(rate))) throw new Error(`That size is not available for ${item.n}.`);
    const qty = Number(raw.qty);
    if (!Number.isInteger(qty) || qty < 1 || qty > 99) throw new Error('Quantity must be a whole number from 1 to 99.');
    return { name: item.n, qty, size, sizeLabel: item[size === 'f' ? 'fLabel' : 'hLabel'] || (size === 'f' ? 'Full' : 'Half'), rate: Number(rate) };
  });
}
function getTotals(items, orderType, taxPercent = state.config.taxPercent) {
  const subtotal = roundMoney(items.reduce((sum, item) => sum + item.qty * item.rate, 0));
  const taxAmount = roundMoney(Math.round(subtotal * Number(taxPercent || 0)) / 100);
  const deliveryFee = orderType === 'delivery' ? roundMoney(state.config.deliveryFee) : 0;
  return { subtotal, taxAmount, taxPercent: Number(taxPercent || 0), deliveryFee, total: roundMoney(subtotal + taxAmount + deliveryFee) };
}
function makeOrder(payload, source = 'customer') {
  if (source === 'customer' && !state.config.isOpen) throw Object.assign(new Error('The restaurant is not accepting orders right now.'), { status: 409 });
  if (!isObject(payload)) throw new Error('Order details are invalid.');
  const orderType = ['dine-in', 'takeaway', 'delivery'].includes(payload.orderType) ? payload.orderType : 'takeaway';
  const customerName = cleanText(payload.customerName, 80);
  const phone = cleanText(payload.phone, 20);
  const digits = phone.replace(/\D/g, '');
  if (source === 'customer') {
    if (customerName.length < 2) throw new Error('Enter the customer name.');
    if (digits.length < 10 || digits.length > 15) throw new Error('Enter a valid contact number with 10–15 digits.');
    if (orderType === 'dine-in' && !cleanText(payload.tableNumber, 20)) throw new Error('A table number is required for dine-in orders.');
    if (orderType === 'delivery' && !cleanText(payload.address, 400)) throw new Error('A delivery address is required.');
  }
  const items = prepareItems(payload.items, source === 'staff');
  const totals = getTotals(items, orderType);
  if (totals.total <= 0) throw new Error('The order total must be greater than zero.');
  const paymentMethod = ['Cash at counter', 'UPI at counter', 'Cash on delivery'].includes(payload.paymentMethod) ? payload.paymentMethod : 'Cash at counter';
  const now = new Date();
  const order = {
    id: state.nextOrderNo++, items, ...totals,
    done: false, status: 'pending', time: now.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }),
    createdAt: now.toISOString(), source,
    customerName: customerName || (source === 'staff' ? 'Walk-in' : ''), phone: source === 'customer' ? phone : '',
    orderType, tableNumber: orderType === 'dine-in' ? cleanText(payload.tableNumber, 20) : '',
    address: orderType === 'delivery' ? cleanText(payload.address, 400) : '', notes: cleanText(payload.notes, 300),
    paymentMethod, paymentStatus: 'unpaid'
  };
  state.orders.unshift(order);
  saveState();
  return order;
}
function validateRestoredOrder(raw) {
  if (!isObject(raw) || raw.id === undefined || !Array.isArray(raw.items)) throw new Error('Backup contains an invalid order.');
  const id = Number(raw.id); if (!Number.isSafeInteger(id) || id < 1) throw new Error('Backup contains an invalid order number.');
  const items = raw.items.map(item => {
    const name = cleanText(item.name, 80); const rate = Number(item.rate); const qty = Number(item.qty);
    if (!name || !Number.isFinite(rate) || rate < 0 || !Number.isInteger(qty) || qty < 1 || qty > 999) throw new Error('Backup contains an invalid order line.');
    return { name, qty, rate, size: item.size === 'f' ? 'f' : 'h', sizeLabel: cleanText(item.sizeLabel, 24) };
  });
  const status = ['pending', 'preparing', 'done', 'cancelled'].includes(raw.status) ? raw.status : 'pending';
  const orderType = ['dine-in', 'takeaway', 'delivery'].includes(raw.orderType) ? raw.orderType : 'takeaway';
  const subtotal = roundMoney(items.reduce((sum, item) => sum + item.qty * item.rate, 0));
  const taxPercent = Math.max(0, Math.min(100, Number(raw.taxPercent) || 0));
  const taxAmount = roundMoney(Number(raw.taxAmount) || 0);
  const deliveryFee = roundMoney(Math.max(0, Number(raw.deliveryFee) || 0));
  return {
    ...raw, id, items, status, done: status === 'done', orderType, subtotal, taxPercent, taxAmount,
    deliveryFee, total: roundMoney(subtotal + taxAmount + deliveryFee),
    customerName: cleanText(raw.customerName, 80), phone: cleanText(raw.phone, 20),
    tableNumber: cleanText(raw.tableNumber, 20), address: cleanText(raw.address, 400), notes: cleanText(raw.notes, 300),
    paymentStatus: raw.paymentStatus === 'paid' ? 'paid' : 'unpaid', createdAt: typeof raw.createdAt === 'string' ? raw.createdAt : new Date().toISOString()
  };
}

const server = http.createServer(async (req, res) => {
  res.setHeader('X-Frame-Options', 'SAMEORIGIN');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  let url;
  try { url = new URL(req.url, `http://${req.headers.host || 'localhost'}`); }
  catch (_) { return sendText(res, 400, 'Bad request'); }
  const pathname = decodeURIComponent(url.pathname);

  try {
    if (req.method === 'GET' && pathname === '/api/health') {
      return sendJson(res, 200, { service: 'Charlie Chaap Service API', version: 1, status: 'ok' });
    }
    if (req.method === 'GET' && pathname === '/api/public-config') {
      return sendJson(res, 200, { config: state.config, updatedAt: state.updatedAt });
    }
    if (req.method === 'GET' && pathname === '/api/auth/session') {
      return sendJson(res, 200, { authenticated: !!currentSession(req) });
    }
    if (req.method === 'POST' && pathname === '/api/auth/login') {
      if (!rateLimit(req, 'login', 8, 15 * 60 * 1000)) return sendJson(res, 429, { error: 'Too many sign-in attempts. Try again in 15 minutes.' });
      const body = await readBody(req, 16 * 1024);
      if (!equalSecret(body.password || '', ADMIN_PASSWORD)) return sendJson(res, 401, { error: 'Incorrect staff password.' });
      const token = crypto.randomBytes(32).toString('base64url');
      sessions.set(token, { createdAt: Date.now(), expiresAt: Date.now() + SESSION_TTL_MS });
      setSessionCookie(req, res, token);
      return sendJson(res, 200, { authenticated: true, expiresIn: SESSION_TTL_MS });
    }
    if (req.method === 'POST' && pathname === '/api/auth/logout') {
      const found = currentSession(req); if (found) sessions.delete(found.token);
      setSessionCookie(req, res, '', true);
      return sendJson(res, 200, { authenticated: false });
    }
    if (req.method === 'GET' && pathname === '/api/menu') {
      if (url.searchParams.get('all') === '1') {
        if (!requireStaff(req, res)) return;
        return sendJson(res, 200, { items: state.menu, updatedAt: state.updatedAt });
      }
      // Public menu data includes availability flags so customers can see sold-out dishes.
      return sendJson(res, 200, { items: state.menu, updatedAt: state.updatedAt });
    }
    if (req.method === 'PUT' && pathname === '/api/menu') {
      if (!requireStaff(req, res)) return;
      const body = await readBody(req);
      state.menu = cleanMenu(body.items);
      saveState();
      return sendJson(res, 200, { ok: true, count: state.menu.length, updatedAt: state.updatedAt });
    }
    if (req.method === 'PUT' && pathname === '/api/service-config') {
      if (!requireStaff(req, res)) return;
      const body = await readBody(req);
      state.config = cleanConfig(body, state.config);
      saveState();
      return sendJson(res, 200, { ok: true, config: state.config, updatedAt: state.updatedAt });
    }
    if (req.method === 'POST' && pathname === '/api/orders') {
      if (!rateLimit(req, 'customer-order', 25, 60 * 1000)) return sendJson(res, 429, { error: 'Too many orders from this connection. Wait a moment and retry.' });
      const body = await readBody(req, 256 * 1024);
      const order = makeOrder(body, 'customer');
      return sendJson(res, 201, { ok: true, order });
    }
    if (req.method === 'POST' && pathname === '/api/orders/manual') {
      if (!requireStaff(req, res)) return;
      const body = await readBody(req, 256 * 1024);
      const order = makeOrder(body, 'staff');
      return sendJson(res, 201, { ok: true, order });
    }
    const orderStatusMatch = pathname.match(/^\/api\/orders\/([^/]+)\/status$/);
    if (req.method === 'GET' && orderStatusMatch) {
      if (!rateLimit(req, 'order-status', 90, 15 * 60 * 1000)) return sendJson(res, 429, { error: 'Too many tracking checks. Try again in a few minutes.' });
      const id = String(orderStatusMatch[1]);
      const order = state.orders.find(item => String(item.id) === id);
      if (!order) return sendJson(res, 404, { error: 'Order not found.' });
      return sendJson(res, 200, { order: { id: order.id, status: order.status, total: order.total, itemCount: order.items.reduce((n, item) => n + item.qty, 0), time: order.time } });
    }
    if (pathname === '/api/orders' && req.method === 'GET') {
      if (!requireStaff(req, res)) return;
      return sendJson(res, 200, { orders: state.orders, updatedAt: state.updatedAt });
    }
    const orderMatch = pathname.match(/^\/api\/orders\/([^/]+)$/);
    if (orderMatch && req.method === 'PATCH') {
      if (!requireStaff(req, res)) return;
      const id = String(orderMatch[1]); const order = state.orders.find(item => String(item.id) === id);
      if (!order) return sendJson(res, 404, { error: 'Order not found.' });
      const body = await readBody(req, 256 * 1024);
      if (body.status !== undefined) {
        const status = String(body.status);
        if (!['pending', 'preparing', 'done', 'cancelled'].includes(status)) return sendJson(res, 400, { error: 'Invalid order status.' });
        order.status = status; order.done = status === 'done';
        if (status === 'cancelled') order.cancelledAt = new Date().toISOString();
      }
      if (body.paymentStatus !== undefined) {
        if (!['paid', 'unpaid'].includes(String(body.paymentStatus))) return sendJson(res, 400, { error: 'Invalid payment status.' });
        order.paymentStatus = body.paymentStatus;
        if (body.paymentStatus === 'paid') order.paidAt = new Date().toISOString();
      }
      if (body.items !== undefined) {
        if (['done', 'cancelled'].includes(order.status)) return sendJson(res, 409, { error: 'Completed or cancelled orders cannot be edited.' });
        const items = prepareItems(body.items, true);
        const totals = getTotals(items, order.orderType, order.taxPercent);
        order.items = items; order.subtotal = totals.subtotal; order.taxAmount = totals.taxAmount;
        order.deliveryFee = order.orderType === 'delivery' ? Number(order.deliveryFee) || state.config.deliveryFee : 0;
        order.total = roundMoney(order.subtotal + order.taxAmount + order.deliveryFee);
      }
      order.updatedAt = new Date().toISOString(); saveState();
      return sendJson(res, 200, { ok: true, order });
    }
    if (orderMatch && req.method === 'DELETE') {
      if (!requireStaff(req, res)) return;
      const id = String(orderMatch[1]); const i = state.orders.findIndex(item => String(item.id) === id);
      if (i < 0) return sendJson(res, 404, { error: 'Order not found.' });
      state.orders.splice(i, 1); saveState();
      return sendJson(res, 200, { ok: true });
    }
    if (req.method === 'GET' && pathname === '/api/backup') {
      if (!requireStaff(req, res)) return;
      return sendJson(res, 200, { app: 'Charlie Chaap Remote Backup', version: 1, exportedAt: new Date().toISOString(), data: state });
    }
    if (req.method === 'POST' && pathname === '/api/backup/restore') {
      if (!requireStaff(req, res)) return;
      const body = await readBody(req);
      const incoming = body.data;
      if (!isObject(incoming) || !Array.isArray(incoming.orders) || !Array.isArray(incoming.menu)) throw new Error('Backup has an invalid structure.');
      const menu = cleanMenu(incoming.menu);
      if (incoming.orders.length > 50000) throw new Error('Backup contains too many orders.');
      const orders = incoming.orders.map(validateRestoredOrder).sort((a, b) => Number(b.id) - Number(a.id));
      const largestId = orders.reduce((max, order) => Math.max(max, order.id), 0);
      state = { version: 1, orders, menu, config: cleanConfig(incoming.config || {}), nextOrderNo: Math.max(Number(incoming.nextOrderNo) || 1, largestId + 1), updatedAt: new Date().toISOString() };
      saveState();
      return sendJson(res, 200, { ok: true, count: orders.length, menuCount: menu.length });
    }

    if (req.method === 'GET' && pathname === '/') {
      res.writeHead(302, { Location: '/customer', 'Cache-Control': 'no-store' }); res.end(); return;
    }
    if (req.method === 'GET' && ['/customer', '/customer/', '/staff', '/staff/', '/admin', '/admin/', '/index.html', '/Charlie_Chaap_Service_Ready.html'].includes(pathname)) {
      const file = path.join(__dirname, 'public', 'index.html');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'same-origin' });
      fs.createReadStream(file).pipe(res); return;
    }
    sendJson(res, 404, { error: 'Route not found.' });
  } catch (error) {
    const status = Number(error.status) || 400;
    if (status >= 500) console.error(error);
    sendJson(res, status, { error: cleanText(error.message || 'Request failed.', 500) });
  }
});

server.listen(PORT, HOST, () => {
  console.log('Charlie Chaap LOCAL NETWORK service is running. Internet is not required.');
  console.log(`This device: http://localhost:${PORT}`);
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const entries of Object.values(interfaces)) {
    for (const info of entries || []) {
      if (info && info.family === 'IPv4' && !info.internal) addresses.push(info.address);
    }
  }
  if (addresses.length) {
    console.log('Open one of these addresses on phones connected to the SAME Wi-Fi/router:');
    for (const address of [...new Set(addresses)]) { console.log(`  Customer website: http://${address}:${PORT}/customer`); console.log(`  Staff dashboard:  http://${address}:${PORT}/staff`); console.log(`  Owner controls:   http://${address}:${PORT}/admin`); }
  } else {
    console.log('No LAN IPv4 address detected yet. Connect this device to the restaurant Wi-Fi and restart.');
  }
  console.log(`Persistent local data: ${STATE_FILE}`);
  console.log('Keep this server device and router powered on during service.');
});

function shutdown() {
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5000).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
