'use strict';

const $ = (id) => document.getElementById(id);
const form = $('reminder-form');
const feedback = $('form-feedback');
const retryKey = 'textloop.pendingReuse';
let mode = 'create';
let selected = null;
let originalMessageDisplay = '';
let dirty = false;
let saving = false;
let needsReview = false;
let uncertainCreate = false;
let checkedCreate = false;
let detailRead = 0;
const listReads = { upcoming: 0, recent: 0 };
let pendingReuse = null;
let retryStorageBlocked = false;

function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function button(text, action) {
  const element = node('button', text);
  element.type = 'button';
  element.addEventListener('click', action);
  return element;
}

function notice(text) { $('notice').textContent = text; }
function localTime(value) { return value.replace('T', ' '); }
function newSchedule(value) { return value.length === 16 ? value + ':00' : value; }

function deliveryText(reminder) {
  switch (reminder.feedback) {
    case 'WAITING': return 'Waiting for simulated sending.';
    case 'ACCEPTED': return 'Sender acceptance recorded. This does not confirm delivery to a phone.';
    case 'WITHDRAWN': return 'Withdrawn. This alert will not send.';
    case 'RETRY_WAITING': return 'Waiting to retry, no earlier than ' + localTime(reminder.nextAttemptAt) + '.';
    case 'RETRY_EXHAUSTED': return 'Sending attempts stopped. Delivery review is needed before another attempt.';
    case 'PERMANENT_REJECTION': return 'The sender rejected this alert. You can choose Remind me again.';
    case 'DELIVERY_UNCERTAIN': return 'Sending could not be confirmed. Delivery review is needed before scheduling again.';
    case 'LEGACY_SENT': return 'Previously marked sent. Acceptance evidence is unavailable.';
    case 'UNTRACKED':
    case 'INCONSISTENT_DELIVERY': return 'Delivery information needs review. Changes are unavailable.';
    default: return 'Refresh details for delivery information.';
  }
}

// Only fixed error codes are displayed through our own copy, never response text.
async function request(path, method = 'GET', body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch('/api/reminders' + path, {
      method, cache: 'no-store', signal: controller.signal,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    let data;
    try { data = await response.json(); } catch { /* A lost save response is ambiguous too. */ }
    if (!response.ok) throw { status: response.status, code: data?.code };
    if (!data || typeof data !== 'object' || (method !== 'GET' && !isReminder(data))) throw { status: 0 };
    return data;
  } finally { clearTimeout(timeout); }
}

function isReminder(value) {
  return value && Number.isSafeInteger(value.id) && value.id > 0
    && typeof value.message === 'string' && typeof value.phoneNumber === 'string'
    && typeof value.scheduledAt === 'string' && Number.isSafeInteger(value.version);
}

function controls() {
  $('fields').disabled = saving || uncertainCreate || (mode === 'reuse' && pendingReuse !== null);
  $('save').disabled = saving || needsReview || (mode === 'reuse' && (pendingReuse !== null || retryStorageBlocked));
  $('new-reminder').disabled = saving;
  document.querySelectorAll('[data-selection]').forEach((item) => { item.disabled = saving; });
  $('saved-retry').querySelectorAll('button').forEach((item) => { item.disabled = saving; });
}

function canLeave() {
  if (saving) return false;
  if (uncertainCreate) {
    notice('This save might have succeeded. Check Upcoming and Recent, then choose Start again after checking.');
    return false;
  }
  return !dirty || window.confirm('Discard the unsaved changes in this form?');
}

function showForm(nextMode, reminder = null, reviewed = false) {
  if (!reviewed && !canLeave()) return;
  ++detailRead;
  notice('');
  mode = nextMode;
  selected = reminder;
  dirty = false;
  needsReview = false;
  feedback.replaceChildren();
  $('detail').hidden = true;
  form.hidden = false;
  $('editor-title').textContent = nextMode === 'create' ? 'New reminder'
    : nextMode === 'edit' ? 'Edit reminder' : 'Remind me again';
  $('message').value = reminder?.message ?? '';
  originalMessageDisplay = $('message').value;
  $('phone').value = reminder?.phoneNumber ?? '';
  $('message').readOnly = nextMode === 'reuse';
  $('phone').readOnly = nextMode !== 'create';
  $('scheduled-at').value = nextMode === 'edit' ? reminder.scheduledAt.slice(0, 16) : '';
  $('time-help').textContent = nextMode === 'edit'
    ? 'Current time: ' + localTime(reminder.scheduledAt) + '. Leave the time unchanged to keep it exactly, or choose a new future time.'
    : nextMode === 'reuse' ? 'Same message and phone number. Choose a new future time.' : 'Choose a future time.';
  $('save').textContent = nextMode === 'edit' ? 'Save changes' : nextMode === 'reuse' ? 'Schedule again' : 'Save reminder';
  controls();
  $('editor-title').focus();
}

function summary(reminder, container) {
  container.append(node('p', reminder.message, 'message'),
    node('p', 'To: ' + reminder.phoneNumber),
    node('p', 'Scheduled: ' + localTime(reminder.scheduledAt)),
    node('p', deliveryText(reminder), 'hint'));
}

function renderDetail(reminder) {
  const detail = $('detail');
  detail.replaceChildren(node('h3', 'Saved reminder'));
  summary(reminder, detail);
  const actions = node('div', undefined, 'actions');
  if (!uncertainCreate && reminder.canEdit) actions.append(button('Edit', () => openAction('edit', reminder.id)));
  if (!uncertainCreate && reminder.canWithdraw) actions.append(button('Withdraw', async () => {
    if (!canLeave() || !window.confirm('Withdraw this alert so it will not send?')) return;
    selected = reminder;
    await mutate('withdraw', reminder.id, { expectedVersion: reminder.version });
  }));
  if (!uncertainCreate && reminder.canRemindAgain) actions.append(button('Remind me again', () => openAction('reuse', reminder.id)));
  for (const action of actions.children) action.dataset.selection = '';
  detail.append(actions);
  detail.hidden = false;
  controls();
}

async function openAction(nextMode, id) {
  if (!canLeave()) return;
  const read = ++detailRead;
  notice('Loading reminder...');
  try {
    const reminder = await request('/' + id);
    if (read !== detailRead) return;
    if (nextMode === 'edit' ? reminder.canEdit : reminder.canRemindAgain) {
      showForm(nextMode, reminder, true);
      notice('');
    } else {
      renderDetail(reminder);
      notice('This alert is no longer available for that change. Current details are shown below.');
    }
  } catch {
    if (read === detailRead) notice('Could not refresh this reminder. Try again before making a change.');
  }
}

async function openDetail(id, review = false) {
  const keepDraft = review || uncertainCreate;
  if (saving || (!keepDraft && !canLeave())) return;
  const read = ++detailRead;
  notice('Loading reminder...');
  try {
    const reminder = await request('/' + id);
    if (read !== detailRead) return;
    if (!keepDraft) {
      mode = 'view'; selected = reminder; dirty = false; needsReview = false;
      form.hidden = true; feedback.replaceChildren();
      $('editor-title').textContent = 'Reminder';
    }
    renderDetail(reminder);
    notice(keepDraft ? (form.hidden ? 'Current saved details are shown below. Review them before making another change.'
      : 'Saved details are shown below your draft. Review them before starting again.') : '');
    if (keepDraft) $('detail').scrollIntoView({ block: 'nearest' });
    else $('editor-title').focus();
  } catch (failure) {
    if (read === detailRead) notice(failure.status === 404 ? 'This reminder is no longer available.' : 'Could not load this reminder. Try View again.');
  }
}

async function refreshList(name) {
  const read = ++listReads[name];
  const target = $(name);
  target.replaceChildren(node('p', 'Loading...'));
  target.setAttribute('aria-busy', 'true');
  try {
    const reminders = await request('/' + name);
    if (!Array.isArray(reminders)) throw {};
    if (read !== listReads[name]) return false;
    const list = node('ul');
    for (const reminder of reminders) {
      if (!isReminder(reminder)) throw {};
      const row = node('li');
      summary(reminder, row);
      const view = button('View', () => openDetail(reminder.id));
      view.dataset.selection = '';
      row.append(view);
      list.append(row);
    }
    target.replaceChildren(reminders.length ? list : node('p', name === 'upcoming' ? 'No upcoming reminders.' : 'No reminders scheduled in the last seven days.'));
    controls();
    return true;
  } catch {
    if (read === listReads[name]) target.replaceChildren(node('p', 'Could not load reminders.'), button('Try again', () => refreshList(name)));
    return false;
  } finally {
    if (read === listReads[name]) target.removeAttribute('aria-busy');
  }
}

async function refreshLists() {
  const results = await Promise.all([refreshList('upcoming'), refreshList('recent')]);
  if (uncertainCreate && results.every(Boolean)) {
    checkedCreate = true;
    createUncertainty();
  }
}

function createUncertainty() {
  feedback.replaceChildren(node('p', 'The save might have succeeded. Check Upcoming and Recent before deciding to save another alert.'));
  feedback.append(button('Check Upcoming and Recent', async () => {
    await refreshLists();
    $('lists').focus();
  }));
  const restart = button('Start again after checking', () => {
    if (!checkedCreate || !window.confirm('The earlier save may already exist. Start again with this draft anyway?')) return;
    uncertainCreate = false; checkedCreate = false;
    feedback.replaceChildren(node('p', 'Draft kept. Save only if you still need another alert.'));
    controls();
    $('save').focus();
  });
  restart.disabled = !checkedCreate;
  feedback.append(restart);
}

function reviewRequired(text) {
  needsReview = true;
  feedback.replaceChildren(node('p', text), button('Review current reminder', () => openDetail(selected.id, true)));
  // Previously rendered actions also belong to an old snapshot.
  $('detail').hidden = true;
}

function mutationError(kind, failure) {
  const uncertain = !failure.status || failure.status >= 500;
  if (uncertain && kind === 'create') {
    uncertainCreate = true; checkedCreate = false; createUncertainty();
  } else if (kind === 'reuse' && uncertain) {
    feedback.replaceChildren(node('p', 'The save might have succeeded. Use Retry this save to check the same alert.'));
  } else if (uncertain) {
    reviewRequired('The save might have succeeded. Review the current reminder before making another change.');
  } else if (failure.code === 'STALE_VERSION') {
    reviewRequired('This reminder changed. Your draft is kept. Review current details before starting again.');
  } else if (failure.code === 'NOT_EDITABLE' || failure.code === 'NOT_REUSABLE' || failure.code === 'DELIVERY_REVIEW_REQUIRED') {
    reviewRequired(failure.code === 'DELIVERY_REVIEW_REQUIRED'
      ? 'Sending could not be confirmed. Delivery review is needed before scheduling again.'
      : 'This alert is no longer available for that change. Review its current details.');
  } else if (failure.code === 'OPERATION_CONFLICT') {
    feedback.replaceChildren(node('p', 'This saved retry does not match the earlier save. Check Upcoming and Recent before starting again.'));
  } else {
    feedback.replaceChildren(node('p', failure.code === 'INVALID_SCHEDULE'
      ? 'Choose a time that is still in the future.'
      : failure.status === 404 ? 'This reminder is no longer available.'
      : 'Could not save. Check the required message, phone number, and date/time.'));
    if (failure.code === 'INVALID_SCHEDULE' && !form.hidden) setTimeout(() => $('scheduled-at').focus(), 0);
  }
}

function validRetry(value) {
  const time = document.createElement('input');
  time.type = 'datetime-local';
  time.step = '60';
  time.value = typeof value?.scheduledAt === 'string' ? value.scheduledAt : '';
  return time.value !== '' && time.checkValidity() && newSchedule(time.value) === value.scheduledAt && value && Object.keys(value).length === 4
    && Number.isSafeInteger(value.sourceId) && value.sourceId > 0
    && Number.isSafeInteger(value.expectedVersion) && value.expectedVersion >= 0
    && typeof value.operationId === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.operationId)
    && typeof value.scheduledAt === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00$/.test(value.scheduledAt);
}

function restoreRetry() {
  try {
    const saved = sessionStorage.getItem(retryKey);
    if (saved === null) return;
    const value = JSON.parse(saved);
    if (!validRetry(value)) throw {};
    pendingReuse = Object.freeze(value);
  } catch { retryStorageBlocked = true; }
}

function storeRetry(value) {
  try {
    sessionStorage.setItem(retryKey, JSON.stringify(value));
    if (sessionStorage.getItem(retryKey) !== JSON.stringify(value)) throw {};
    pendingReuse = Object.freeze(value);
    renderRetry();
    return true;
  } catch {
    retryStorageBlocked = true;
    renderRetry();
    feedback.replaceChildren(node('p', 'This browser could not keep track of the save, so nothing was submitted. Other reminder forms and browsing are still available.'));
    controls();
    return false;
  }
}

function clearRetry() {
  try {
    sessionStorage.removeItem(retryKey);
    if (sessionStorage.getItem(retryKey) !== null) throw {};
    pendingReuse = null; retryStorageBlocked = false;
  } catch {
    retryStorageBlocked = true;
    notice('Could not update the saved retry. Try reloading this page before using Remind me again.');
  }
  renderRetry();
}

function renderRetry() {
  const panel = $('saved-retry');
  panel.hidden = pendingReuse === null && !retryStorageBlocked;
  panel.replaceChildren();
  if (panel.hidden) return;
  panel.append(node('p', pendingReuse
    ? 'A saved Remind me again request is available. Retry checks that same save, without creating a second alert if it already succeeded.'
    : 'Saved retry information is unavailable. Check Upcoming and Recent before scheduling again.'));
  const actions = node('div', undefined, 'actions');
  if (pendingReuse) {
    const retry = button('Retry this save', () => {
      if (!canLeave()) return;
      const { sourceId, ...body } = pendingReuse;
      mode = 'view'; selected = { id: sourceId }; dirty = false; needsReview = false;
      form.hidden = true; $('detail').hidden = true; feedback.replaceChildren();
      $('editor-title').textContent = 'Remind me again';
      mutate('reuse', sourceId, body);
    });
    retry.disabled = saving;
    actions.append(retry);
  }
  const clear = button('Clear saved retry', () => {
    if (saving || !window.confirm('The earlier save might already exist. Check Upcoming and Recent before scheduling another alert. Stop keeping this retry?')) return;
    clearRetry(); controls();
  });
  clear.disabled = saving;
  actions.append(clear);
  panel.append(actions);
}

async function mutate(kind, id, body) {
  if (saving) return;
  saving = true;
  ++detailRead;
  controls();
  notice('Saving...');
  let result;
  try {
    result = await request(kind === 'create' ? '' : '/' + id + (kind === 'edit' ? '' : kind === 'reuse' ? '/remind-again' : '/withdraw'),
      kind === 'edit' ? 'PUT' : 'POST', body);
  } catch (failure) {
    notice('');
    // A definitive refusal cannot have committed this request. A key conflict needs manual review.
    if (kind === 'reuse' && failure.status >= 400 && failure.status < 500 && failure.code !== 'OPERATION_CONFLICT') clearRetry();
    mutationError(kind, failure);
    return;
  } finally {
    saving = false;
    controls();
  }
  if (kind === 'reuse') clearRetry();
  mode = 'view'; selected = result; dirty = false; needsReview = false;
  form.hidden = true; feedback.replaceChildren();
  $('editor-title').textContent = 'Reminder';
  renderDetail(result);
  const savedText = kind === 'withdraw' ? 'Alert withdrawn.' : 'Reminder saved.';
  notice(savedText);
  $('editor-title').focus();
  const read = ++detailRead;
  try {
    const detail = await request('/' + result.id);
    if (read === detailRead) { selected = detail; renderDetail(detail); }
  } catch {
    if (read === detailRead) notice(savedText + ' Details could not refresh. Use View to check them.');
  }
  await refreshLists();
}

form.addEventListener('input', () => { dirty = true; ++detailRead; });
form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (saving || needsReview || uncertainCreate || !form.reportValidity()) return;
  const time = $('scheduled-at').value;
  if (mode === 'reuse') {
    if (pendingReuse || retryStorageBlocked) return;
    let operationId;
    try { operationId = crypto.randomUUID(); } catch {
      feedback.replaceChildren(node('p', 'This browser cannot prepare a safe save. Try a current browser on this device.'));
      return;
    }
    const saved = { sourceId: selected.id, expectedVersion: selected.version, operationId, scheduledAt: newSchedule(time) };
    if (!storeRetry(saved)) return;
    const { sourceId, ...body } = saved;
    await mutate('reuse', sourceId, body);
  } else if (mode === 'edit') {
    // The native minute control cannot represent the original microsecond timestamp.
    const scheduledAt = time === selected.scheduledAt.slice(0, 16) ? selected.scheduledAt : newSchedule(time);
    // Preserve stored line endings when only the time changes. Textareas display CRLF as LF.
    const message = $('message').value === originalMessageDisplay ? selected.message : $('message').value;
    await mutate('edit', selected.id, { message, scheduledAt, expectedVersion: selected.version });
  } else if (mode === 'create') {
    await mutate('create', null, { message: $('message').value, phoneNumber: $('phone').value, scheduledAt: newSchedule(time) });
  }
});

$('new-reminder').addEventListener('click', () => showForm('create'));
$('refresh').addEventListener('click', () => refreshLists());
window.addEventListener('beforeunload', (event) => {
  if (saving || uncertainCreate || dirty) { event.preventDefault(); event.returnValue = ''; }
});
restoreRetry();
renderRetry();
controls();
refreshLists();
