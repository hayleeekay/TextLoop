'use strict';

const $ = (id) => document.getElementById(id);
const form = $('reminder-form');
const feedback = $('form-feedback');
const retryKey = 'textloop.pendingReuse';
const demoNumber = '+12035550100';
const themes = ['system', 'light', 'dark'];
let mode = 'view';
let activeView = 'upcoming';
let selected = null;
let originalMessageDisplay = '';
let dirty = false;
let saving = false;
let needsReview = false;
let uncertainCreate = false;
let checkedCreate = false;
let detailRead = 0;
const listReads = { upcoming: 0, history: 0 };
let pendingReuse = null;
let retryStorageBlocked = false;
let composerDraft = { mode: 'create' };
let draftBeforeReuse = null;
let editFromMessages = false;
let recipient = preference('textloop.recipient', demoNumber);
const loaded = { upcoming: null, history: null };
let toastTimer;

function preference(key, fallback) {
  try { return localStorage.getItem(key) || fallback; }
  catch { return fallback; }
}

function setPreference(key, value) {
  try { localStorage.setItem(key, value); return true; }
  catch { return false; }
}

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
function toast(text, duration = 0) {
  clearTimeout(toastTimer);
  $('toast').textContent = text;
  if (duration) toastTimer = setTimeout(() => toast(''), duration);
}
function localTime(value) { return value.replace('T', ' '); }
function newSchedule(value) { return value.length === 16 ? value + ':00' : value; }

function displayTime(value) {
  // Format the stored wall-clock fields without applying a timezone offset.
  const date = new Date(value + 'Z');
  return Number.isNaN(date.getTime()) ? localTime(value)
    : new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric',
      hour: 'numeric', minute: '2-digit', timeZone: 'UTC' }).format(date);
}

function deliveryText(reminder) {
  switch (reminder.feedback) {
    case 'WAITING': return 'Waiting for simulated sending.';
    case 'ACCEPTED': return 'Sender acceptance recorded. This does not confirm delivery to a phone.';
    case 'WITHDRAWN': return 'Withdrawn. This reminder is no longer active.';
    case 'RETRY_WAITING': return reminder.withdrawnAt ? 'The last attempt was rejected. Further attempts have been stopped.'
      : 'Waiting to retry, no earlier than ' + localTime(reminder.nextAttemptAt) + '.';
    case 'RETRY_EXHAUSTED': return reminder.withdrawnAt ? 'Sending attempts stopped. You can choose Remind me again.'
      : 'Sending attempts stopped. Delivery review is needed before another attempt.';
    case 'PERMANENT_REJECTION': return 'The sender rejected this alert. You can choose Remind me again.';
    case 'DELIVERY_UNCERTAIN': return 'Sending could not be confirmed. Delivery review is needed before scheduling again.';
    case 'LEGACY_SENT': return 'Previously marked sent. Acceptance evidence is unavailable.';
    case 'UNTRACKED':
    case 'INCONSISTENT_DELIVERY': return 'Delivery information needs review. Editing and scheduling again are unavailable.';
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
  $('new-reminder').disabled = saving || uncertainCreate;
  $('back').disabled = saving;
  document.querySelectorAll('[data-navigation]').forEach((item) => { item.disabled = saving; });
  document.querySelectorAll('[data-selection]').forEach((item) => { item.disabled = saving || uncertainCreate; });
  $('saved-retry').querySelectorAll('button').forEach((item) => { item.disabled = saving; });
}

function isComposer() { return mode === 'create' || mode === 'reuse'; }

function navigation(id) {
  document.querySelectorAll('[data-navigation]').forEach((tab) => {
    if (tab.id === id) tab.setAttribute('aria-current', 'page');
    else tab.removeAttribute('aria-current');
  });
}

function scheduleValue() {
  const date = $('send-date').value;
  const time = $('send-time').value;
  return date && time ? date + 'T' + time : '';
}

function deviceTime(date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function tomorrowMorning() {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  date.setHours(9, 0, 0, 0);
  return deviceTime(date);
}

function resizeMessage() {
  const field = $('message');
  field.style.height = '';
  if (isComposer() && field.clientWidth) {
    field.style.height = '0px';
    field.style.height = field.scrollHeight + 'px';
  }
}

function rememberComposer() {
  if ($('editor').hidden || !isComposer()) return;
  composerDraft = {
    mode, selected, originalMessageDisplay, dirty, needsReview,
    text: $('message').value, date: $('send-date').value, time: $('send-time').value
  };
}

function canLeave() {
  if (saving) return false;
  if (uncertainCreate) {
    notice('This save might have succeeded. Check Schedule and History, then choose Start again after checking.');
    return false;
  }
  return isComposer() || !dirty || window.confirm('Discard the unsaved changes in this form?');
}

function showForm(nextMode, reminder = null, { reviewed = false, draft = {}, focus = true } = {}) {
  if (!reviewed && !canLeave()) return;
  ++detailRead;
  notice('');
  mode = nextMode;
  selected = reminder;
  dirty = draft.dirty ?? false;
  needsReview = draft.needsReview ?? false;
  feedback.replaceChildren();
  $('detail').hidden = true;
  $('browse').hidden = true;
  $('settings').hidden = true;
  $('editor').hidden = false;
  form.hidden = false;
  $('editor').classList.toggle('composer', isComposer());
  $('editor').setAttribute('aria-labelledby', isComposer() ? 'conversation-title' : 'editor-title');
  $('back').hidden = isComposer();
  $('edit-heading').hidden = isComposer();
  if (nextMode === 'edit') {
    $('editor-title').textContent = 'Edit reminder';
    $('editor-help').textContent = 'Make a change before this alert sends.';
  }
  $('conversation').hidden = !isComposer();
  $('cancel').hidden = nextMode === 'create';
  $('draft-recipient').hidden = nextMode !== 'reuse' || reminder.phoneNumber === recipient;
  $('draft-recipient').textContent = nextMode === 'reuse' ? 'Uses the original number: ' + reminder.phoneNumber : '';
  $('draft-actions').hidden = nextMode === 'create';
  $('message').value = draft.text ?? reminder?.message ?? '';
  resizeMessage();
  originalMessageDisplay = draft.originalMessageDisplay ?? $('message').value;
  const [date, time] = (nextMode === 'edit' ? reminder.scheduledAt.slice(0, 16)
    : nextMode === 'create' ? tomorrowMorning() : '').split('T');
  $('send-date').value = draft.date ?? date;
  $('send-time').value = draft.time ?? time ?? '';
  $('save').textContent = isComposer() ? '↑' : 'Save changes';
  $('save').setAttribute('aria-label', isComposer() ? 'Schedule text' : 'Save changes');
  if (isComposer()) navigation('messages-tab');
  if (needsReview) reviewRequired('Review the saved reminder before scheduling again.');
  controls();
  if (focus) {
    const target = nextMode === 'reuse' ? 'send-date' : nextMode === 'create' ? 'conversation-title' : 'editor-title';
    $(target).focus();
  }
}

function openMessages() {
  if (saving) return;
  if (uncertainCreate) {
    $('settings').hidden = true;
    $('browse').hidden = true;
    $('editor').hidden = false;
    navigation('messages-tab');
    $('conversation-title').focus();
    return;
  }
  rememberComposer();
  showForm(composerDraft.mode, composerDraft.selected, { draft: composerDraft });
}

function finishComposer() {
  composerDraft = draftBeforeReuse ?? { mode: 'create' };
  draftBeforeReuse = null;
  showForm(composerDraft.mode, composerDraft.selected, { reviewed: true, draft: composerDraft });
}

function showBrowse(name = activeView, saved = false) {
  if (saving || (!uncertainCreate && !saved && !canLeave())) return false;
  if (!uncertainCreate && !saved) rememberComposer();
  ++detailRead;
  activeView = name;
  if (!uncertainCreate) {
    mode = 'view'; selected = null; dirty = false; needsReview = false;
    form.hidden = true; feedback.replaceChildren(); $('detail').hidden = true;
  }
  $('editor').hidden = true;
  $('settings').hidden = true;
  $('browse').hidden = false;
  $('upcoming').hidden = name !== 'upcoming';
  $('history').hidden = name !== 'history';
  navigation(name === 'upcoming' ? 'home' : 'history-tab');
  $('new-reminder').hidden = name !== 'upcoming';
  $('view-title').textContent = name === 'upcoming' ? 'Reminders' : 'Last 7 days';
  $('view-help').textContent = name === 'upcoming' ? '' : 'Sent and withdrawn reminders';
  if (!saved) notice('');
  controls();
  $('view-title').focus();
  return true;
}

function openSettings() {
  if (!canLeave()) return;
  rememberComposer();
  ++detailRead;
  mode = 'view'; selected = null; dirty = false; needsReview = false;
  $('editor').hidden = true;
  $('browse').hidden = true;
  $('detail').hidden = true;
  feedback.replaceChildren();
  notice('');
  $('phone').value = recipient;
  $('settings').hidden = false;
  navigation('settings-tab');
}

function summary(reminder, container) {
  container.append(node('p', reminder.message, 'message'),
    node('p', 'To: ' + reminder.phoneNumber),
    node('p', 'Scheduled: ' + localTime(reminder.scheduledAt)),
    node('p', deliveryText(reminder), 'hint'));
  if (reminder.withdrawnAt) container.append(node('p', 'Withdrawn: ' + localTime(reminder.withdrawnAt), 'hint'));
}

function renderDetail(reminder) {
  const detail = $('detail');
  detail.replaceChildren(node('h2', 'Current saved version'));
  summary(reminder, detail);
  detail.hidden = false;
}

async function openAction(nextMode, id) {
  if (!canLeave()) return;
  if (needsReview && selected?.id === id) {
    notice('Review the current reminder before making another change.');
    return;
  }
  const read = ++detailRead;
  notice('Loading reminder...');
  try {
    const reminder = await request('/' + id);
    if (read !== detailRead) return;
    const allowed = nextMode === 'edit' ? reminder.canEdit
      : nextMode === 'reuse' ? reminder.canRemindAgain : reminder.canWithdraw;
    if (!allowed) {
      notice('This alert is no longer available for that change. Refreshing its saved details.');
      await refreshLists();
    } else if (nextMode === 'withdraw') {
      notice('');
      if (!window.confirm('Withdraw this reminder? Further attempts will stop, but an attempt already in progress may still send.')) return;
      selected = reminder;
      await mutate('withdraw', reminder.id, { expectedVersion: reminder.version });
    } else if (nextMode === 'reuse') {
      rememberComposer();
      if (composerDraft.mode === 'create') draftBeforeReuse = composerDraft;
      showForm('reuse', reminder, { reviewed: true });
      rememberComposer();
    } else {
      editFromMessages = !$('editor').hidden && isComposer();
      rememberComposer();
      showForm(nextMode, reminder, { reviewed: true });
    }
  } catch {
    if (read === detailRead) notice('Could not refresh this reminder. Try again before making a change.');
  }
}

async function reviewCurrentReminder() {
  if (saving || !selected) return;
  const id = selected.id;
  const keepDraft = !$('editor').hidden && !form.hidden;
  const read = ++detailRead;
  notice('Loading reminder...');
  try {
    const reminder = await request('/' + id);
    if (read !== detailRead) return;
    if (!keepDraft) {
      mode = 'review'; form.hidden = true;
      $('browse').hidden = true; $('editor').hidden = false;
      $('editor').classList.remove('composer');
      $('editor').setAttribute('aria-labelledby', 'editor-title');
      $('back').hidden = false;
      $('edit-heading').hidden = false;
      $('conversation').hidden = true;
      $('editor-title').textContent = 'Review reminder';
      $('editor-help').textContent = 'Check the saved details before starting another change.';
    }
    renderDetail(reminder);
    notice(keepDraft ? 'Saved details are shown below your draft. Review them before starting again.' : 'Current saved details are shown below.');
    if (keepDraft) $('detail').scrollIntoView({ block: 'nearest' });
    else $('editor-title').focus();
  } catch (failure) {
    if (read === detailRead) notice(failure.status === 404 ? 'This reminder is no longer available.' : 'Could not load the saved reminder. Try Review current reminder again.');
  }
}

function renderCard(reminder, name) {
  const row = node('li', undefined, 'reminder-card');
  row.dataset.feedback = reminder.feedback;
  row.append(node('h2', reminder.message, 'message'));
  const labels = { WAITING: 'Waiting to send',
    ACCEPTED: 'Sent (simulated)', WITHDRAWN: 'Withdrawn', RETRY_WAITING: 'Retry scheduled',
    RETRY_EXHAUSTED: 'Sending stopped', PERMANENT_REJECTION: 'Not sent',
    DELIVERY_UNCERTAIN: 'Needs review', LEGACY_SENT: 'Previously sent',
    UNTRACKED: 'Needs review', INCONSISTENT_DELIVERY: 'Needs review' };
  const attention = ['RETRY_WAITING', 'RETRY_EXHAUSTED', 'PERMANENT_REJECTION',
    'DELIVERY_UNCERTAIN', 'UNTRACKED', 'INCONSISTENT_DELIVERY'].includes(reminder.feedback);
  const meta = node('div', undefined, 'meta');
  const time = reminder.withdrawnAt
    ? 'Withdrawn ' + displayTime(reminder.withdrawnAt)
    : displayTime(reminder.scheduledAt);
  meta.append(node('span', time));
  if (!reminder.withdrawnAt && reminder.feedback !== 'WAITING') {
    meta.append(node('span', labels[reminder.feedback] ?? 'Needs review', attention ? 'badge attention' : 'badge'));
  }
  if (reminder.phoneNumber !== recipient) meta.append(node('span', 'To ' + reminder.phoneNumber));
  row.append(meta);
  if (attention) row.append(node('p', deliveryText(reminder), 'attention-text'));
  const actions = node('div', undefined, 'actions');
  if (reminder.canEdit) actions.append(button('Edit', () => openAction('edit', reminder.id)));
  if (name === 'upcoming' && reminder.canWithdraw) actions.append(button('Withdraw', () => openAction('withdraw', reminder.id)));
  if (reminder.canRemindAgain) {
    const again = button('Again', () => openAction('reuse', reminder.id));
    again.className = 'again';
    again.setAttribute('aria-label', 'Again: schedule ' + reminder.message);
    actions.append(again);
  }
  for (const action of actions.children) action.dataset.selection = '';
  if (actions.children.length) row.append(actions);
  return row;
}

function renderSchedule(reminders) {
  const scheduled = reminders.filter((reminder) => reminder.feedback === 'WAITING');
  const attention = reminders.filter((reminder) => reminder.feedback !== 'WAITING');
  const result = document.createDocumentFragment();
  for (const [heading, items] of [
    ['Next up', scheduled.slice(0, 1)], ['Needs attention', attention], ['Coming up', scheduled.slice(1)]
  ]) {
    if (!items.length) continue;
    const group = node('section', undefined, 'schedule-group');
    const list = node('ul');
    for (const reminder of items) list.append(renderCard(reminder, 'upcoming'));
    group.append(node('h2', heading), list);
    result.append(group);
  }
  return result;
}

function renderThread() {
  const thread = $('thread');
  const sent = (loaded.history ?? []).filter((item) => ['ACCEPTED', 'LEGACY_SENT'].includes(item.feedback) && !item.withdrawnAt);
  const scheduled = (loaded.upcoming ?? []).filter((item) => item.feedback === 'WAITING');
  const visible = [...sent.slice(0, 3).reverse(), ...scheduled.slice(0, 3)];
  thread.replaceChildren();
  for (const reminder of visible) {
    const item = node('li', undefined, 'bubble-row');
    const bubble = node('p', reminder.message, 'message bubble');
    if (reminder.feedback === 'WAITING') bubble.classList.add('scheduled');
    item.append(bubble);
    const details = node('div', undefined, 'bubble-meta hint');
    const status = reminder.feedback === 'WAITING' ? 'Scheduled for ' + displayTime(reminder.scheduledAt)
      : reminder.feedback === 'LEGACY_SENT' ? 'Previously sent' : 'Sent (simulated)';
    details.append(node('span', status));
    if (reminder.canEdit) {
      const edit = button('Edit', () => openAction('edit', reminder.id));
      edit.className = 'quiet';
      edit.dataset.selection = '';
      edit.setAttribute('aria-label', 'Edit ' + reminder.message);
      details.append(edit);
    }
    item.append(details);
    thread.append(item);
  }
  if (!visible.length && loaded.upcoming && loaded.history) thread.append(node('li', 'No messages yet.', 'hint thread-empty'));
}

async function refreshList(name, background = false) {
  const read = ++listReads[name];
  const target = $(name);
  if (!background) {
    target.setAttribute('aria-busy', 'true');
  }
  try {
    const reminders = await request(name === 'upcoming' ? '/home' : '/history');
    if (!Array.isArray(reminders)) throw {};
    if (read !== listReads[name]) return false;
    for (const reminder of reminders) {
      if (!isReminder(reminder)) throw {};
    }
    if (background && (saving || target.contains(document.activeElement)
      || $('thread').contains(document.activeElement) || JSON.stringify(loaded[name]) === JSON.stringify(reminders))) {
      return true;
    }
    loaded[name] = reminders;
    renderThread();
    const list = name === 'upcoming' ? renderSchedule(reminders) : node('ul');
    if (name === 'history') for (const reminder of reminders) list.append(renderCard(reminder, name));
    const empty = node('div', undefined, 'empty');
    empty.append(node('h2', name === 'upcoming' ? 'No reminders waiting' : 'Nothing here yet.'));
    if (name === 'upcoming') empty.append(node('p', 'Schedule a text whenever you need a reminder.', 'hint'));
    target.replaceChildren(reminders.length ? list : empty);
    controls();
    return true;
  } catch {
    if (!background && loaded[name] === null && read === listReads[name]) {
      target.replaceChildren(node('p', 'Could not load reminders.'), button('Try again', () => refreshList(name)));
    }
    return false;
  } finally {
    if (read === listReads[name]) target.removeAttribute('aria-busy');
  }
}

function refreshInBackground() {
  if (document.hidden || saving || uncertainCreate || document.querySelector('#upcoming[aria-busy], #history[aria-busy]')) return;
  void refreshList('upcoming', true);
  void refreshList('history', true);
}

async function refreshLists() {
  const checking = uncertainCreate;
  const results = await Promise.all([refreshList('upcoming'), refreshList('history')]);
  if (checking && uncertainCreate && results.every(Boolean)) {
    checkedCreate = true;
    createUncertainty();
  }
  return results.every(Boolean);
}

function createUncertainty() {
  feedback.replaceChildren(node('p', 'The save might have succeeded. Check Schedule and History before deciding to schedule another alert.'));
  feedback.append(button('Check saved reminders', async () => {
    showBrowse();
    await refreshLists();
    $('view-title').focus();
  }));
  feedback.append(button('Return to draft', () => {
    openMessages();
  }));
  const close = button('Close draft', () => {
    if (!checkedCreate || !window.confirm('Close this unsaved draft? Any reminder already saved will stay scheduled.')) return;
    uncertainCreate = false; checkedCreate = false; dirty = false;
    composerDraft = { mode: 'create' };
    showBrowse(activeView, true);
    notice('Draft closed. Saved reminders are unchanged.');
  });
  close.disabled = !checkedCreate;
  feedback.append(close);
  const restart = button('Start again after checking', () => {
    if (!checkedCreate || !window.confirm('The earlier save may already exist. Start again with this draft anyway?')) return;
    uncertainCreate = false; checkedCreate = false;
    $('browse').hidden = true; $('editor').hidden = false;
    navigation('messages-tab');
    feedback.replaceChildren(node('p', 'Draft kept. Save only if you still need another alert.'));
    controls();
    $('save').focus();
  });
  restart.disabled = !checkedCreate;
  feedback.append(restart);
}

function reviewRequired(text) {
  needsReview = true;
  feedback.replaceChildren(node('p', text), button('Review current reminder', reviewCurrentReminder));
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
    reviewRequired(!$('editor').hidden && !form.hidden
      ? 'This reminder changed. Your draft is kept. Review current details before starting again.'
      : 'This reminder changed. Review current details before starting again.');
  } else if (failure.code === 'NOT_EDITABLE' || failure.code === 'NOT_REUSABLE' || failure.code === 'DELIVERY_REVIEW_REQUIRED') {
    reviewRequired(failure.code === 'DELIVERY_REVIEW_REQUIRED'
      ? 'Sending could not be confirmed. Delivery review is needed before scheduling again.'
      : 'This alert is no longer available for that change. Review its current details.');
  } else if (failure.code === 'OPERATION_CONFLICT') {
    feedback.replaceChildren(node('p', 'This saved retry does not match the earlier save. Check Schedule and History before starting again.'));
  } else {
    feedback.replaceChildren(node('p', failure.code === 'INVALID_SCHEDULE'
      ? 'Choose a time that is still in the future.'
      : failure.status === 404 ? 'This reminder is no longer available.'
      : 'Could not save. Check the required message, phone number, and date/time.'));
    if (failure.code === 'INVALID_SCHEDULE' && !form.hidden) {
      setTimeout(() => $('send-date').focus(), 0);
    }
  }
}

function validRetry(value) {
  const time = document.createElement('input');
  time.type = 'datetime-local';
  time.step = '60';
  time.value = typeof value?.scheduledAt === 'string' ? value.scheduledAt : '';
  return time.value !== '' && time.checkValidity() && newSchedule(time.value) === value.scheduledAt
    && Object.keys(value).length === (Object.hasOwn(value, 'message') ? 5 : 4)
    && (!Object.hasOwn(value, 'message') || (typeof value.message === 'string' && value.message.length > 0
      && value.message.length <= 1000 && !value.message.includes('\0')))
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
    : 'Saved retry information is unavailable. Check Schedule and History before scheduling again.'));
  const actions = node('div', undefined, 'actions');
  if (pendingReuse) {
    const retry = button('Retry this save', () => {
      if (!canLeave()) return;
      const { sourceId, ...body } = pendingReuse;
      rememberComposer();
      showBrowse(activeView, true);
      mode = 'view'; selected = { id: sourceId }; dirty = false; needsReview = false;
      mutate('reuse', sourceId, body);
    });
    retry.disabled = saving;
    actions.append(retry);
  }
  const clear = button('Clear saved retry', () => {
    if (saving || !window.confirm('The earlier save might already exist. Check Schedule and History before scheduling another alert. Stop keeping this retry?')) return;
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
  notice('');
  toast('Saving…');
  try {
    await request(kind === 'create' ? '' : '/' + id + (kind === 'edit' ? '' : kind === 'reuse' ? '/remind-again' : '/withdraw'),
      kind === 'edit' ? 'PUT' : 'POST', body);
  } catch (failure) {
    toast('');
    // A definitive refusal cannot have committed this request. A key conflict needs manual review.
    if (kind === 'reuse' && failure.status >= 400 && failure.status < 500 && failure.code !== 'OPERATION_CONFLICT') clearRetry();
    mutationError(kind, failure);
    return;
  } finally {
    saving = false;
    controls();
  }
  if (kind === 'reuse') clearRetry();
  dirty = false; needsReview = false;
  if (kind === 'create' || (kind === 'reuse' && composerDraft.selected?.id === id)) {
    finishComposer();
  } else if (kind === 'reuse' || (kind === 'edit' && editFromMessages)) {
    showForm(composerDraft.mode, composerDraft.selected, { reviewed: true, draft: composerDraft });
  } else {
    showBrowse(kind === 'withdraw' ? 'history' : 'upcoming', true);
  }
  const savedText = kind === 'withdraw' ? 'Reminder withdrawn.' : kind === 'edit' ? 'Changes saved.' : 'Reminder scheduled.';
  toast(savedText, 4000);
  await refreshLists();
}

form.addEventListener('input', (event) => {
  dirty = true;
  ++detailRead;
  if (event.target === $('message')) resizeMessage();
});
form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (saving || needsReview || uncertainCreate || !form.reportValidity()) return;
  const time = scheduleValue();
  if (mode === 'reuse') {
    if (pendingReuse || retryStorageBlocked) return;
    let operationId;
    try { operationId = crypto.randomUUID(); } catch {
      feedback.replaceChildren(node('p', 'This browser cannot prepare a safe save. Try a current browser on this device.'));
      return;
    }
    const saved = { sourceId: selected.id, expectedVersion: selected.version, operationId, scheduledAt: newSchedule(time) };
    if ($('message').value !== originalMessageDisplay) saved.message = $('message').value;
    rememberComposer();
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
    await mutate('create', null, { message: $('message').value, phoneNumber: recipient, scheduledAt: newSchedule(time) });
  }
});

$('messages-tab').addEventListener('click', openMessages);
$('new-reminder').addEventListener('click', openMessages);
$('settings-tab').addEventListener('click', openSettings);
for (const [id, name] of [['home', 'upcoming'], ['history-tab', 'history']]) {
  $(id).addEventListener('click', () => { if (showBrowse(name)) refreshLists(); });
}
for (const id of ['back', 'cancel']) $(id).addEventListener('click', () => {
  if (mode === 'reuse') finishComposer();
  else if (mode === 'edit' && editFromMessages) openMessages();
  else if (showBrowse()) refreshLists();
});
$('recipient-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const value = $('phone').value.trim();
  if (!value || value.includes('\0')) {
    notice('Enter a number for your reminders.');
    $('phone').focus();
    return;
  }
  recipient = value;
  notice(setPreference('textloop.recipient', value) ? 'Number saved.' : 'Number set for this visit.');
});
$('theme').value = preference('textloop.theme', 'system');
if (!themes.includes($('theme').value)) $('theme').value = 'system';
document.documentElement.dataset.theme = $('theme').value;
$('theme').addEventListener('change', () => {
  document.documentElement.dataset.theme = $('theme').value;
  setPreference('textloop.theme', $('theme').value);
});
const palette = $('palette');
const savedPalette = preference('textloop.palette', 'purple');
const paletteChoices = [...palette.querySelectorAll('input')];
const selectedPalette = paletteChoices.find((choice) => choice.value === savedPalette) ?? paletteChoices[0];
selectedPalette.checked = true;
document.documentElement.dataset.palette = selectedPalette.value;
palette.addEventListener('change', (event) => {
  document.documentElement.dataset.palette = event.target.value;
  setPreference('textloop.palette', event.target.value);
});
window.addEventListener('resize', resizeMessage);
document.addEventListener('visibilitychange', refreshInBackground);
setInterval(refreshInBackground, 60000);
window.addEventListener('beforeunload', (event) => {
  if (saving || uncertainCreate || dirty || composerDraft.dirty || draftBeforeReuse?.dirty) {
    event.preventDefault(); event.returnValue = '';
  }
});
restoreRetry();
renderRetry();
showForm('create', null, { focus: false });
refreshLists();
