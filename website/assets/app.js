(() => {
  const toggle = document.querySelector('.menu-toggle');
  const nav = document.querySelector('#main-nav');
  const closeMenu = () => {
    toggle?.setAttribute('aria-expanded', 'false');
    toggle?.setAttribute('aria-label', 'Open navigation');
    nav?.classList.remove('open');
  };
  toggle?.addEventListener('click', () => {
    const open = toggle.getAttribute('aria-expanded') !== 'true';
    toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
    nav.classList.toggle('open', open);
  });
  nav?.addEventListener('click', event => { if (event.target.closest('a')) closeMenu(); });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && toggle?.getAttribute('aria-expanded') === 'true') {
      closeMenu(); toggle.focus();
    }
  });
  window.matchMedia('(min-width: 561px)').addEventListener('change', closeMenu);

  const form = document.querySelector('#support-form');
  if (!form) return;
  const app = form.elements.app;
  const selectedApp = new URLSearchParams(window.location.search).get('app');
  if (['photo', 'security', 'website'].includes(selectedApp)) app.value = selectedApp;
  form.addEventListener('submit', event => {
    event.preventDefault();
    if (!form.reportValidity()) return;
    const appName = app.options[app.selectedIndex].text;
    const subject = `${appName} — ${form.elements.topic.value}`;
    const body = `App: ${appName}\nTopic: ${form.elements.topic.value}\n\n${form.elements.message.value.trim()}\n\nAndroid version (if relevant):\nApp version (if known):`;
    const url = `mailto:${form.dataset.email}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
    document.querySelector('#form-feedback').textContent = `If your email app doesn't open, email ${form.dataset.email} directly. Your message has not been sent yet.`;
    window.location.href = url;
  });
})();
