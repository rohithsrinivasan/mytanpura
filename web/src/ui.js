// Small DOM toolkit for the control panel.
//
// Every builder returns { el, refresh }. The refresh functions are collected by
// the caller and run whenever state changes, so a slider drag updates its own
// readout without tearing down and rebuilding the DOM - which would lose focus
// and interrupt the drag on touch devices.

/** Terse element factory. */
export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k === 'html') el.innerHTML = v;
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else el.setAttribute(k, v);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

/**
 * A labelled range slider.
 *
 * The input is only written from `refresh` when it is not being dragged,
 * otherwise the thumb would fight the user's finger on every state round-trip.
 */
export function slider({ label, min, max, step, get, set, format, hint, disabled }) {
  const value = h('span', { class: 'slider-value' });
  const input = h('input', {
    type: 'range',
    min: String(min),
    max: String(max),
    step: String(step),
    'aria-label': label,
  });
  let dragging = false;

  input.addEventListener('pointerdown', () => {
    dragging = true;
  });
  const endDrag = () => {
    dragging = false;
  };
  input.addEventListener('pointerup', endDrag);
  input.addEventListener('pointercancel', endDrag);
  input.addEventListener('blur', endDrag);
  input.addEventListener('input', () => {
    set(parseFloat(input.value));
  });
  input.addEventListener('change', endDrag);

  const labelRow = h('div', { class: 'slider-head' }, h('span', { text: label }), value);
  const hintEl = hint ? h('p', { class: 'hint' }) : null;
  const el = h('div', { class: 'slider' }, labelRow, input, hintEl);

  const refresh = () => {
    const v = get();
    if (!dragging) input.value = String(v);
    value.textContent = format ? format(v) : String(v);
    const off = disabled ? disabled() : false;
    input.disabled = off;
    el.classList.toggle('is-disabled', off);
    if (hintEl) hintEl.textContent = typeof hint === 'function' ? hint() : hint;
  };
  refresh();
  return { el, refresh };
}

/**
 * Scrolls a horizontal strip so `el` is centred, without touching any ancestor.
 *
 * `Element.scrollIntoView` walks *every* scrollable ancestor, so calling it on a
 * chip inside the scrolling control panel silently scrolls the panel too - which
 * pushed the first card's heading and slider off the top of the screen. Setting
 * scrollLeft directly only moves the strip.
 */
export function centreHorizontally(container, el) {
  const target = el.offsetLeft - (container.clientWidth - el.offsetWidth) / 2;
  const max = container.scrollWidth - container.clientWidth;
  container.scrollLeft = Math.max(0, Math.min(max, target));
}

/** A horizontally scrolling single-choice chip row. */
export function chips({ options, getSelected, onSelect, keyOf, labelOf, ariaLabel }) {
  const row = h('div', { class: 'chips', role: 'radiogroup', 'aria-label': ariaLabel || '' });
  const buttons = options.map((option) => {
    const key = keyOf(option);
    const btn = h('button', {
      type: 'button',
      class: 'chip',
      role: 'radio',
      onClick: () => onSelect(option),
      text: labelOf(option),
      dataset: { key: String(key) },
    });
    row.append(btn);
    return { btn, key };
  });

  let lastSelected;
  const refresh = () => {
    const selected = getSelected();
    for (const { btn, key } of buttons) {
      const on = key === selected;
      btn.classList.toggle('is-on', on);
      btn.setAttribute('aria-checked', on ? 'true' : 'false');
      // Only reposition when the choice actually changed, so a user who has
      // scrolled the strip by hand is not yanked back on every state update.
      if (on && selected !== lastSelected) centreHorizontally(row, btn);
    }
    lastSelected = selected;
  };
  refresh();
  return { el: row, refresh };
}

/** A card with a heading, optionally collapsible. */
export function card({ title, children, collapsible = false, open = false, trailing }) {
  const body = h('div', { class: 'card-body' }, ...children.map((c) => c.el || c));
  const heading = h('h2', { class: 'card-title', text: title });
  const head = h('div', { class: 'card-head' }, heading);

  if (trailing) head.append(trailing);

  let expanded = open;
  if (collapsible) {
    const toggle = h('button', {
      type: 'button',
      class: 'card-toggle',
      'aria-expanded': String(expanded),
      text: expanded ? '−' : '+',
    });
    toggle.addEventListener('click', () => {
      expanded = !expanded;
      body.hidden = !expanded;
      toggle.textContent = expanded ? '−' : '+';
      toggle.setAttribute('aria-expanded', String(expanded));
    });
    head.append(toggle);
    body.hidden = !expanded;
  }

  const el = h('section', { class: 'card' }, head, body);
  const refresh = () => {
    for (const c of children) if (c.refresh) c.refresh();
  };
  return { el, refresh };
}

/** A row with a label, sub-label and a switch. */
export function toggleRow({ label, sub, get, set }) {
  const input = h('input', { type: 'checkbox', 'aria-label': label });
  input.addEventListener('change', () => set(input.checked));
  const el = h(
    'label',
    { class: 'toggle-row' },
    h('span', {}, h('strong', { text: label }), sub ? h('small', { text: sub }) : null),
    h('span', { class: 'switch' }, input, h('span', { class: 'switch-track' })),
  );
  const refresh = () => {
    input.checked = get();
  };
  refresh();
  return { el, refresh };
}

export function button({ text, onClick, variant = 'primary', full = false }) {
  const el = h('button', {
    type: 'button',
    class: `btn btn-${variant}${full ? ' btn-full' : ''}`,
    text,
    onClick,
  });
  return { el, refresh: () => {} };
}

export function hint(text) {
  const el = h('p', { class: 'hint' });
  const refresh = () => {
    el.textContent = typeof text === 'function' ? text() : text;
  };
  refresh();
  return { el, refresh };
}

export function row(...children) {
  const el = h('div', { class: 'row' }, ...children.map((c) => c.el || c));
  return { el, refresh: () => children.forEach((c) => c.refresh && c.refresh()) };
}

/** Transient message at the bottom of the screen. */
export function toast(message) {
  let host = document.querySelector('.toast-host');
  if (!host) {
    host = h('div', { class: 'toast-host' });
    document.body.append(host);
  }
  const el = h('div', { class: 'toast', text: message });
  host.append(el);
  requestAnimationFrame(() => el.classList.add('is-in'));
  setTimeout(() => {
    el.classList.remove('is-in');
    setTimeout(() => el.remove(), 300);
  }, 2600);
}

export function formatDuration(totalSeconds) {
  const s = Math.max(0, Math.round(totalSeconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
