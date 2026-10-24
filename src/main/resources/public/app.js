'use strict';

// Minor-unit scale per currency, matching the server's Currencies rules.
const SCALE = { EUR: 2, USD: 2, GBP: 2, PLN: 2, INR: 2, JPY: 0 };
const CURRENCIES = Object.keys(SCALE);

const $ = (selector, root = document) => root.querySelector(selector);

const state = {
  accounts: [],
  labels: new Map(),   // account id -> display name, users and system accounts alike
  rates: new Map(),    // "EUR:USD" -> rate string
  mode: 'transfer',
  last: null,          // the last money request: { mode, body, key }
  openTx: null,        // id of the transaction whose journal is expanded
};

// ---------- Money: exact arithmetic in minor units (BigInt), never floats ----------

function toMinor(amount, currency) {
  const scale = SCALE[currency] ?? 2;
  const negative = amount.startsWith('-');
  const [whole, fraction = ''] = amount.replace('-', '').split('.');
  const minor = BigInt(whole + fraction.padEnd(scale, '0').slice(0, scale));
  return negative ? -minor : minor;
}

function fromMinor(minor, currency) {
  const scale = SCALE[currency] ?? 2;
  const negative = minor < 0n;
  const digits = (negative ? -minor : minor).toString().padStart(scale + 1, '0');
  const whole = scale ? digits.slice(0, -scale) : digits;
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return (negative ? '-' : '') + grouped + (scale ? '.' + digits.slice(-scale) : '');
}

const money = (amount, currency) => fromMinor(toMinor(amount, currency), currency);

// ---------- DOM helpers (text is always inserted as text, never as HTML) ----------

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : String(child));
  }
  return node;
}

function toast(code, message, bad = false) {
  const node = el('div', { class: bad ? 'toast bad' : 'toast', role: bad ? 'alert' : 'status' },
    el('strong', {}, code), message);
  $('#toasts').append(node);
  setTimeout(() => node.remove(), 5000);
}

const shortId = id => id.slice(0, 8) + '…';

function formatTime(iso) {
  const date = new Date(iso);
  const sameDay = date.toDateString() === new Date().toDateString();
  return sameDay
    ? date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : date.toLocaleString([], { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function newKey() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'key-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

// ---------- API ----------

async function api(method, path, body, idempotencyKey) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { error: 'BAD_RESPONSE', message: text };
  }
  return {
    status: response.status,
    ok: response.ok,
    replayed: response.headers.get('Idempotent-Replayed') === 'true',
    data,
  };
}

function labelFor(account) {
  if (account.kind === 'EXTERNAL') return `External funding · ${account.currency}`;
  if (account.kind === 'FX_POOL') return `FX pool · ${account.currency}`;
  return `${account.ownerId} · ${account.currency}`;
}

async function ensureLabels(ids) {
  const missing = [...new Set(ids)].filter(id => !state.labels.has(id));
  const results = await Promise.all(missing.map(id => api('GET', `/accounts/${id}`)));
  results.forEach((result, i) => state.labels.set(missing[i], result.ok ? labelFor(result.data) : shortId(missing[i])));
}

const accountById = id => state.accounts.find(account => account.id === id);

// ---------- Loading and rendering ----------

async function load() {
  try {
    const [health, accounts, transactions, reconciliation] = await Promise.all([
      api('GET', '/health'),
      api('GET', '/accounts?limit=200'),
      api('GET', '/transactions?limit=25'),
      api('GET', '/admin/reconciliation'),
    ]);
    setPill('#health-pill', health.ok, health.ok ? 'API online' : 'API error');
    state.accounts = accounts.ok ? accounts.data : [];
    state.accounts.forEach(account => state.labels.set(account.id, labelFor(account)));

    const recent = transactions.ok ? transactions.data : [];
    await ensureLabels(recent.flatMap(tx => [tx.sourceAccountId, tx.destinationAccountId, ...tx.entries.map(e => e.accountId)]));

    renderKpis(recent, reconciliation.ok ? reconciliation.data : null);
    renderAccounts();
    renderSelects();
    renderTransactions(recent);
  } catch (error) {
    setPill('#health-pill', false, 'API offline');
    toast('NETWORK_ERROR', 'Cannot reach the ledger API. Is the server running?', true);
  }
}

function setPill(selector, good, text) {
  const pill = $(selector);
  pill.classList.toggle('good', good);
  pill.classList.toggle('bad', !good);
  pill.lastElementChild.textContent = text;
}

function renderKpis(transactions, reconciliation) {
  $('#kpi-accounts').textContent = state.accounts.length;
  const currencies = [...new Set(state.accounts.map(account => account.currency))].sort();
  $('#kpi-currencies').textContent = currencies.length;

  const funds = $('#kpi-funds');
  funds.replaceChildren();
  const totals = new Map();
  for (const account of state.accounts) {
    totals.set(account.currency, (totals.get(account.currency) ?? 0n) + toMinor(account.balance, account.currency));
  }
  const held = [...totals].filter(([, total]) => total !== 0n).sort(([a], [b]) => a.localeCompare(b));
  if (held.length === 0) funds.append(el('span', { class: 'muted' }, 'No balances yet'));
  for (const [currency, total] of held.slice(0, 4)) {
    funds.append(el('div', { class: 'row' }, el('span', { class: 'ccy' }, currency), el('span', {}, fromMinor(total, currency))));
  }

  $('#kpi-transactions').textContent = transactions.length;
  $('#kpi-tx-note').textContent = transactions.length === 25 ? 'latest 25 shown' : 'deposits and transfers';

  const recon = $('#kpi-recon');
  if (!reconciliation) {
    recon.textContent = '–';
    setPill('#recon-pill', false, 'Ledger unknown');
    return;
  }
  recon.textContent = reconciliation.balanced ? 'Balanced' : 'Out of balance';
  recon.className = 'kpi-value ' + (reconciliation.balanced ? 'good' : 'bad');
  const nets = Object.entries(reconciliation.netByCurrency).map(([currency, net]) => `${currency} ${net}`);
  $('#kpi-recon-note').textContent = nets.length ? 'Net: ' + nets.join(' · ') : 'every currency must net to 0';
  setPill('#recon-pill', reconciliation.balanced, reconciliation.balanced ? 'Ledger balanced' : 'Ledger out of balance');
}

function renderAccounts() {
  const body = $('#accounts-table tbody');
  body.replaceChildren(...state.accounts.map(account => el('tr', { class: 'clickable', onclick: () => openDrawer(account.id) },
    el('td', { class: 'owner' }, account.ownerId),
    el('td', {}, el('span', { class: 'ccy-badge' }, account.currency)),
    el('td', { class: 'num' }, money(account.balance, account.currency)),
    el('td', { class: 'id', title: account.id }, shortId(account.id)),
    el('td', { class: 'num' }, el('button', { class: 'btn ghost small', type: 'button' }, 'Statement')),
  )));
  $('#accounts-empty').hidden = state.accounts.length > 0;
  $('#accounts-table').hidden = state.accounts.length === 0;
  // Demo data is for an empty ledger only, so it cannot be loaded twice by accident.
  $('#seed-btn').hidden = state.accounts.length > 0;
}

function renderSelects() {
  for (const select of document.querySelectorAll('.account-select')) {
    const previous = select.value;
    select.replaceChildren(
      el('option', { value: '' }, state.accounts.length ? 'Choose an account' : 'Open an account first'),
      ...state.accounts.map(account => el('option', { value: account.id },
        `${account.ownerId} · ${account.currency} — ${money(account.balance, account.currency)}`)));
    if (accountById(previous)) select.value = previous;
  }
  const form = $('#money-form');
  if (!form.from.value && state.accounts[0]) form.from.value = state.accounts[0].id;
  if (!form.to.value && state.accounts[1]) form.to.value = state.accounts[1].id;
  updateForm();
}

function renderTransactions(transactions) {
  const body = $('#tx-table tbody');
  const rows = [];
  for (const tx of transactions) {
    const open = state.openTx === tx.id;
    rows.push(el('tr', { class: 'clickable' + (open ? ' open' : ''), onclick: () => toggleJournal(tx.id, transactions) },
      el('td', { class: 'time', title: tx.createdAt }, formatTime(tx.createdAt)),
      el('td', {}, el('span', { class: 'type ' + tx.type.toLowerCase() }, tx.type === 'DEPOSIT' ? 'Deposit' : 'Transfer')),
      el('td', {}, state.labels.get(tx.sourceAccountId)),
      el('td', {}, state.labels.get(tx.destinationAccountId)),
      el('td', { class: 'num' }, `${money(tx.sourceAmount, tx.sourceCurrency)} ${tx.sourceCurrency}`),
      el('td', { class: 'num' }, `${money(tx.destinationAmount, tx.destinationCurrency)} ${tx.destinationCurrency}`),
      el('td', { class: 'num' }, tx.sourceCurrency === tx.destinationCurrency ? '—' : Number(tx.fxRate).toFixed(4)),
    ));
    if (open) rows.push(journalRow(tx));
  }
  body.replaceChildren(...rows);
  $('#tx-empty').hidden = transactions.length > 0;
  $('#tx-table').hidden = transactions.length === 0;
}

function journalRow(tx) {
  const sums = new Map();
  for (const entry of tx.entries) {
    sums.set(entry.currency, (sums.get(entry.currency) ?? 0n) + toMinor(entry.amount, entry.currency));
  }
  return el('tr', { class: 'journal' }, el('td', { colspan: '7' },
    el('div', { class: 'journal-box' },
      el('table', {},
        el('thead', {}, el('tr', {}, el('th', {}, '#'), el('th', {}, 'Account'), el('th', { class: 'num' }, 'Amount'), el('th', {}, 'Currency'))),
        el('tbody', {}, tx.entries.map((entry, i) => el('tr', {},
          el('td', { class: 'id' }, i + 1),
          el('td', {}, state.labels.get(entry.accountId)),
          el('td', { class: 'num ' + (entry.amount.startsWith('-') ? 'neg' : 'pos') },
            (entry.amount.startsWith('-') ? '' : '+') + money(entry.amount, entry.currency)),
          el('td', {}, el('span', { class: 'ccy-badge' }, entry.currency)),
        )))),
      el('div', { class: 'journal-foot' }, [...sums].map(([currency, sum]) =>
        el('span', {}, `${currency} lines sum to ${fromMinor(sum, currency)} ${sum === 0n ? '✓' : '✗'}`))),
    )));
}

function toggleJournal(id, transactions) {
  state.openTx = state.openTx === id ? null : id;
  renderTransactions(transactions);
}

// ---------- Open account ----------

async function openAccount(event) {
  event.preventDefault();
  const form = event.target;
  const result = await api('POST', '/accounts', { ownerId: form.ownerId.value.trim(), currency: form.currency.value });
  if (!result.ok) return toast(result.data.error, result.data.message, true);
  toast('ACCOUNT OPENED', `${result.data.ownerId} · ${result.data.currency}`);
  form.ownerId.value = '';
  await load();
}

// ---------- Move money ----------

function setMode(mode) {
  state.mode = mode;
  for (const seg of document.querySelectorAll('.seg')) seg.setAttribute('aria-selected', String(seg.dataset.mode === mode));
  for (const field of document.querySelectorAll('[data-for]')) field.hidden = !field.dataset.for.split(' ').includes(mode);
  $('#from-label').textContent = mode === 'transfer' ? 'From' : 'Into account';
  $('#submit-btn').textContent = mode === 'transfer' ? 'Send transfer' : 'Deposit funds';
  $('#money-form').to.required = mode === 'transfer';
  updateForm();
}

async function updateForm() {
  const form = $('#money-form');
  const from = accountById(form.from.value);
  const to = accountById(form.to.value);
  $('#amount-ccy').textContent = from ? from.currency : '—';
  form.amount.placeholder = from && SCALE[from.currency] === 0 ? '0' : '0.00';

  const preview = $('#fx-preview');
  if (state.mode !== 'transfer' || !from || !to || from.currency === to.currency) {
    preview.hidden = true;
    return;
  }
  const pair = `${from.currency}:${to.currency}`;
  if (!state.rates.has(pair)) {
    const result = await api('GET', `/fx/rate?from=${from.currency}&to=${to.currency}`);
    if (!result.ok) return;
    state.rates.set(pair, result.data.rate);
  }
  const rate = state.rates.get(pair);
  const amount = Number(form.amount.value);
  preview.replaceChildren();
  if (amount > 0) {
    preview.append(`${to.ownerId} receives about `,
      el('strong', {}, `${(amount * Number(rate)).toFixed(SCALE[to.currency])} ${to.currency}`),
      ` at ${rate} (rounded half-even by the server).`);
  } else {
    preview.append(`Cross-currency: 1 ${from.currency} = `, el('strong', {}, `${rate} ${to.currency}`),
      `, routed through both FX pools.`);
  }
  preview.hidden = false;
}

async function submitMoney(event) {
  event.preventDefault();
  const form = event.target;
  const amount = form.amount.value.trim();
  const body = state.mode === 'transfer'
    ? { fromAccountId: form.from.value, toAccountId: form.to.value, amount }
    : { accountId: form.from.value, amount };
  state.last = { mode: state.mode, body, key: form.key.value.trim() };
  await send(state.last);
}

async function send(request) {
  const button = $('#submit-btn');
  button.disabled = true;
  $('#retry-btn').disabled = true;
  try {
    const result = await api('POST', request.mode === 'transfer' ? '/transfers' : '/deposits', request.body, request.key);
    showResult(result, request);
    if (result.ok) {
      // A new payment needs a new key; the last one stays available for the retry demo.
      const form = $('#money-form');
      if (form.key.value.trim() === request.key) form.key.value = newKey();
      await load();
    }
  } catch (error) {
    toast('NETWORK_ERROR', 'The request did not reach the server.', true);
  } finally {
    button.disabled = false;
    $('#retry-btn').disabled = false;
  }
}

function showResult(result, request) {
  const box = $('#result');
  box.hidden = false;
  box.className = 'result ' + (result.ok ? 'ok' : 'err');
  $('#result-status').textContent = result.ok ? `${result.status} Created` : `${result.status} ${result.data?.error ?? 'ERROR'}`;
  $('#result-replayed').hidden = !result.replayed;
  $('#result-key').textContent = `Idempotency-Key: ${request.key}`;

  const text = $('#result-body');
  text.replaceChildren();
  if (!result.ok) {
    text.append(result.data?.message ?? 'Request failed.');
    if (result.data?.error === 'INSUFFICIENT_FUNDS') text.append(' The key was not used up, so a retry after a top-up will run.');
    return;
  }
  const tx = result.data;
  const amountOf = (value, currency) => el('strong', {}, `${money(value, currency)} ${currency}`);
  if (tx.type === 'DEPOSIT') {
    text.append('Deposited ', amountOf(tx.destinationAmount, tx.destinationCurrency), ` into ${state.labels.get(tx.destinationAccountId) ?? 'the account'}.`);
  } else {
    text.append('Sent ', amountOf(tx.sourceAmount, tx.sourceCurrency), ` from ${state.labels.get(tx.sourceAccountId)}; `,
      `${state.labels.get(tx.destinationAccountId)} received `, amountOf(tx.destinationAmount, tx.destinationCurrency),
      tx.sourceCurrency === tx.destinationCurrency ? '.' : ` at ${tx.fxRate}.`);
  }
  text.append(` Journal: ${tx.entries.length} entries.`);
  if (result.replayed) text.append(' This is the saved response from the first attempt; balances did not change.');
}

// ---------- Account drawer ----------

async function openDrawer(id) {
  const [account, entries] = await Promise.all([api('GET', `/accounts/${id}`), api('GET', `/accounts/${id}/entries?limit=50`)]);
  if (!account.ok) return toast(account.data.error, account.data.message, true);
  const a = account.data;
  $('#drawer-kind').textContent = `${a.kind === 'USER' ? 'Customer' : 'System'} account · ${a.currency}`;
  $('#drawer-title').textContent = a.ownerId;
  $('#drawer-balance').textContent = `${money(a.balance, a.currency)} ${a.currency}`;
  $('#drawer-id').textContent = a.id;
  const list = $('#drawer-entries');
  const items = entries.ok ? entries.data : [];
  list.replaceChildren(...(items.length ? items.map(entry => el('li', {},
    el('div', {},
      el('div', { class: 'desc' }, entry.amount.startsWith('-') ? 'Money out' : 'Money in'),
      el('div', { class: 'when' }, `${formatTime(entry.createdAt)} · tx ${shortId(entry.transactionId)}`)),
    el('div', { class: 'amt ' + (entry.amount.startsWith('-') ? 'neg' : 'pos') },
      (entry.amount.startsWith('-') ? '' : '+') + money(entry.amount, entry.currency)),
  )) : [el('li', { class: 'muted' }, 'No entries yet.')]));
  const drawer = $('#drawer');
  drawer.classList.add('open');
  drawer.setAttribute('aria-hidden', 'false');
  $('.drawer-panel [data-close]').focus();
}

function closeDrawer() {
  const drawer = $('#drawer');
  drawer.classList.remove('open');
  drawer.setAttribute('aria-hidden', 'true');
}

// ---------- Demo data ----------

async function seedDemo() {
  const button = $('#seed-btn');
  button.disabled = true;
  try {
    const open = async (ownerId, currency) => (await api('POST', '/accounts', { ownerId, currency })).data;
    const alice = await open('alice', 'EUR');
    const bob = await open('bob', 'USD');
    const chen = await open('chen', 'GBP');
    const priya = await open('priya', 'INR');
    const deposit = (account, amount) => api('POST', '/deposits', { accountId: account.id, amount }, newKey());
    const transfer = (from, to, amount) => api('POST', '/transfers', { fromAccountId: from.id, toAccountId: to.id, amount }, newKey());
    await deposit(alice, '2500.00');
    await deposit(bob, '1200.00');
    await deposit(chen, '800.00');
    await deposit(priya, '50000.00');
    await transfer(alice, bob, '100.00');
    await transfer(bob, chen, '45.50');
    await transfer(priya, alice, '9050.00');
    await transfer(chen, alice, '20.00');
    toast('DEMO DATA', 'Opened 4 accounts, made 4 deposits and 4 transfers.');
    await load();
  } catch (error) {
    toast('NETWORK_ERROR', 'Could not load demo data.', true);
  } finally {
    button.disabled = false;
  }
}

// ---------- Wiring ----------

function init() {
  const currencySelect = $('.currency-select');
  currencySelect.replaceChildren(...CURRENCIES.map(code => el('option', { value: code }, code)));

  const form = $('#money-form');
  form.key.value = newKey();
  form.addEventListener('submit', submitMoney);
  form.from.addEventListener('change', updateForm);
  form.to.addEventListener('change', updateForm);
  form.amount.addEventListener('input', updateForm);

  $('#open-form').addEventListener('submit', openAccount);
  $('#new-key-btn').addEventListener('click', () => { form.key.value = newKey(); });
  $('#retry-btn').addEventListener('click', () => state.last && send(state.last));
  $('#refresh-btn').addEventListener('click', load);
  $('#seed-btn').addEventListener('click', seedDemo);
  for (const seg of document.querySelectorAll('.seg')) seg.addEventListener('click', () => setMode(seg.dataset.mode));
  for (const close of document.querySelectorAll('[data-close]')) close.addEventListener('click', closeDrawer);
  document.addEventListener('keydown', event => { if (event.key === 'Escape') closeDrawer(); });

  setMode('transfer');
  load();
}

init();
